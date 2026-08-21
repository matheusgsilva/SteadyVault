package com.steadyvault.camera.ui.browser

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import android.webkit.CookieManager
import com.steadyvault.camera.SteadyVaultApplication
import com.steadyvault.camera.core.storage.PrivateStorageGuard
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultCleanupRepository
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.storage.vault.VaultMediaIndex
import com.steadyvault.camera.storage.vault.VaultRepository
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal object SocialMediaDownloader {
    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<String>())
    data class FormatChoice(
        val id: String,
        val title: String,
        val detail: String,
        val selector: String,
        val outputExtension: String,
        val estimatedBytes: Long = 0L,
        val directUrl: String = "",
        val directMimeType: String = "",
        val directReferrer: String = ""
    )

    data class AnalyzedMedia(
        val sourceUrl: String,
        val title: String,
        val extractor: String,
        val formats: List<FormatChoice>,
        val useBrowserSession: Boolean = true,
        val thumbnailUrl: String = ""
    )

    data class DownloadResult(
        val file: File,
        val destinationLabel: String,
        val bytes: Long
    )

    fun canHandle(url: String?): Boolean {
        val clean = url?.trim().orEmpty()
        if (!clean.startsWith("http://", true) && !clean.startsWith("https://", true)) return false
        val host = runCatching { URI(clean).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        return SOCIAL_HOST_HINTS.any { host == it || host.endsWith(".$it") }
    }

    fun analyze(context: Context, rawUrl: String): AnalyzedMedia {
        val url = InstagramPublicAccess.normalize(rawUrl)
        require(isValidHttpUrl(url)) { "Informe um link http/https válido" }
        SteadyVaultApplication.ensureMediaEngine(context.applicationContext as Application, refreshExtractor = true)
        if (InstagramPublicAccess.isInstagramUrl(url)) return analyzeInstagram(context, url)
        return try {
            analyzeOnce(context, url, useBrowserSession = true)
        } catch (error: Throwable) {
            throw IllegalStateException(cleanError(error), error)
        }
    }

    private fun analyzeInstagram(context: Context, url: String): AnalyzedMedia {
        var lastError = try {
            return analyzeOnce(context, url, useBrowserSession = false)
        } catch (error: Throwable) {
            error
        }

        val application = context.applicationContext as Application
        if (SteadyVaultApplication.repairMediaExtractorForCompatibility(application)) {
            try {
                return analyzeOnce(context, url, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        for (candidate in InstagramPublicAccess.fallbackUrls(url)) {
            try {
                return analyzeOnce(context, candidate, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        analyzeInstagramPublicPage(context, url)?.let { return it }

        if (hasInstagramSession(url)) {
            for (candidate in listOf(url) + InstagramPublicAccess.fallbackUrls(url)) {
                try {
                    return analyzeOnce(context, candidate, useBrowserSession = true)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        throw IllegalStateException(cleanInstagramError(lastError), lastError)
    }

    fun download(
        context: Context,
        media: AnalyzedMedia,
        format: FormatChoice,
        destination: String,
        referrer: String,
        cancelled: () -> Boolean = { false },
        onProgress: (percent: Int, message: String) -> Unit
    ): DownloadResult {
        val app = context.applicationContext
        check(!cancelled()) { "Download cancelado" }
        enforceNetworkPolicy(app)
        if (format.directUrl.isNotBlank()) {
            val direct = BrowserVaultDownloader.download(
                context = app,
                url = format.directUrl,
                userAgent = BrowserWebViewConfigurator.currentUserAgent(app),
                cookie = "",
                contentDisposition = "",
                mimeType = format.directMimeType.ifBlank { "video/mp4" },
                destination = destination,
                referrer = format.directReferrer.ifBlank { INSTAGRAM_REFERRER },
                quality = PrivateBrowserStore.mediaQuality(app),
                resolution = PrivateBrowserStore.mediaResolution(app),
                cancelled = cancelled,
                onProgress = onProgress
            )
            return DownloadResult(direct.file, direct.destinationLabel, direct.bytes)
        }
        SteadyVaultApplication.ensureMediaEngine(app as Application, refreshExtractor = true)
        val workDir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.cacheDir, "SocialDownloads/${UUID.randomUUID()}")
        require(workDir.mkdirs() || workDir.isDirectory) { "Não foi possível preparar a pasta temporária" }
        if (PrivateBrowserStore.verifyFreeSpace(app)) PrivateStorageGuard.requireSpace(workDir, format.estimatedBytes.coerceAtLeast(0L) * 2L)
        VaultCleanupRepository.protectTemporary(workDir)
        val processId = "steadyvault-${System.currentTimeMillis()}-${abs(media.sourceUrl.hashCode())}"
        activeProcesses += processId
        var cookieFile: File? = null
        try {
            check(!cancelled()) { "Download cancelado" }
            cookieFile = if (media.useBrowserSession) buildCookiesFile(app, media.sourceUrl) else null
            val outputTemplate = File(workDir, "%(title).140B [%(id)s].%(ext)s").absolutePath
            val request = YoutubeDLRequest(media.sourceUrl).apply {
                addOption("-f", format.selector)
                addOption("-o", outputTemplate)
                addOption("--merge-output-format", if (format.outputExtension == "mkv") "mkv/mp4" else "mp4/mkv")
                addOption("--continue")
                addOption("--newline")
                addOption("--no-mtime")
                addOption("--trim-filenames", "140")
                addOption("--windows-filenames")
                addOption("--no-playlist")
                val performance = PrivateBrowserStore.performanceProfile(app)
                val retries = if (performance == PrivateBrowserStore.PERFORMANCE_FAST) "5" else "10"
                val fragmentRetries = if (performance == PrivateBrowserStore.PERFORMANCE_FAST) "5" else "10"
                addOption("--retries", retries)
                addOption("--fragment-retries", fragmentRetries)
                addOption("--retry-sleep", "fragment:exp=1:8")
                addOption("--socket-timeout", if (performance == PrivateBrowserStore.PERFORMANCE_FAST) "20" else "30")
                addOption("--extractor-retries", if (performance == PrivateBrowserStore.PERFORMANCE_FAST) "3" else "5")
                if (PrivateBrowserStore.aria2Enabled(app) && performance != PrivateBrowserStore.PERFORMANCE_COMPATIBLE) {
                    addOption("--downloader", "libaria2c.so")
                    addOption("--downloader-args", "aria2c:-x8 -s8 -k1M --file-allocation=none")
                }
                if (PrivateBrowserStore.embedMetadata(app)) addOption("--embed-metadata")
                if (referrer.isNotBlank()) addOption("--referer", referrer)
                if (!InstagramPublicAccess.isInstagramUrl(media.sourceUrl)) addOption("--user-agent", BrowserWebViewConfigurator.currentUserAgent(app))
                cookieFile?.let { addOption("--cookies", it.absolutePath) }
                applySiteOptions(app, media.sourceUrl, media.useBrowserSession)
            }
            onProgress(0, "Preparando download…")
            YoutubeDL.getInstance().execute(
                request = request,
                processId = processId,
                callback = { progress, etaSeconds, line ->
                    val percent = progress.toInt().coerceIn(0, 99)
                    onProgress(percent, progressText(line, etaSeconds))
                }
            )
            check(!cancelled()) { "Download cancelado" }
            onProgress(99, "Salvando no cofre…")
            val output = downloadableVideos(workDir).maxByOrNull { it.length() }
                ?: throw IllegalStateException("Nenhum vídeo compatível foi gerado")
            val target = moveToDestination(app, output, destination)
            val item = VaultRepository.readItem(target, fast = false)
                ?: throw IllegalStateException("O vídeo baixado não foi reconhecido pelo cofre")
            onProgress(100, "Salvo em ${PrivateBrowserStore.destinationLabel(destination)}")
            return DownloadResult(item.file, PrivateBrowserStore.destinationLabel(destination), item.sizeBytes)
        } catch (error: Throwable) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            val message = if (InstagramPublicAccess.isInstagramUrl(media.sourceUrl) && !media.useBrowserSession) {
                cleanInstagramError(error)
            } else {
                cleanError(error)
            }
            throw IllegalStateException(message, error)
        } finally {
            activeProcesses -= processId
            runCatching { cookieFile?.delete() }
            runCatching { workDir.deleteRecursively() }
            VaultCleanupRepository.releaseTemporary(cookieFile)
            VaultCleanupRepository.releaseTemporary(workDir)
        }
    }

    fun cancelActiveDownloads() {
        val snapshot = synchronized(activeProcesses) { activeProcesses.toList() }
        snapshot.forEach { processId -> runCatching { YoutubeDL.getInstance().destroyProcessById(processId) } }
        activeProcesses.removeAll(snapshot.toSet())
    }


    private fun enforceNetworkPolicy(context: Context) {
        if (!PrivateBrowserStore.wifiOnly(context)) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = network?.let(manager::getNetworkCapabilities)
        require(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Downloads estão configurados para usar somente Wi‑Fi"
        }
    }

    private fun analyzeOnce(context: Context, url: String, useBrowserSession: Boolean): AnalyzedMedia {
        val app = context.applicationContext
        val cookieFile = if (useBrowserSession) buildCookiesFile(app, url) else null
        try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--no-warnings")
                addOption("--no-playlist")
                addOption("--socket-timeout", "30")
                addOption("--extractor-retries", "5")
                if (!InstagramPublicAccess.isInstagramUrl(url)) addOption("--user-agent", BrowserWebViewConfigurator.currentUserAgent(app))
                cookieFile?.let { addOption("--cookies", it.absolutePath) }
                applySiteOptions(context, url, useBrowserSession)
            }
            val info = YoutubeDL.getInstance().getInfo(request)
            val title = info.title?.takeIf(String::isNotBlank)
                ?: info.fulltitle?.takeIf(String::isNotBlank)
                ?: "Vídeo"
            val extractor = info.extractorKey?.takeIf(String::isNotBlank)
                ?: info.extractor?.takeIf(String::isNotBlank)
                ?: runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            val formats = buildFormatChoices(context, info.formats.orEmpty(), info.formatId, info.ext)
            require(formats.isNotEmpty()) { "Nenhum formato de vídeo disponível para esse link" }
            return AnalyzedMedia(
                sourceUrl = url,
                title = title,
                extractor = extractor,
                formats = formats,
                useBrowserSession = useBrowserSession,
                thumbnailUrl = thumbnailUrlFromInfo(info)
            )
        } finally {
            runCatching { cookieFile?.delete() }
            VaultCleanupRepository.releaseTemporary(cookieFile)
        }
    }

    private fun analyzeInstagramPublicPage(context: Context, sourceUrl: String): AnalyzedMedia? {
        val app = context.applicationContext
        val quality = PrivateBrowserStore.mediaQuality(app)
        val resolution = PrivateBrowserStore.mediaResolution(app)
        for (pageUrl in InstagramPublicAccess.publicPageUrls(sourceUrl)) {
            val candidates = runCatching {
                BrowserMediaExtractor.resolve(
                    pageUrl = pageUrl,
                    userAgent = BrowserWebViewConfigurator.currentUserAgent(app),
                    cookie = "",
                    quality = quality,
                    resolution = resolution
                )
            }.getOrDefault(emptyList())
                .filter(::isDirectVideoCandidate)
                .distinctBy { it.url.substringBefore('#') }
                .take(8)
            if (candidates.isEmpty()) continue

            val formats = candidates.mapIndexed { index, candidate ->
                val extension = directExtension(candidate)
                val label = if (index == 0) "Qualidade original" else "Alternativa ${index + 1}"
                FormatChoice(
                    id = "public-direct-$index",
                    title = label,
                    detail = "${extension.uppercase(Locale.US)} • arquivo público direto",
                    selector = "",
                    outputExtension = extension,
                    directUrl = candidate.url,
                    directMimeType = candidate.mimeType,
                    directReferrer = candidate.referrer.ifBlank { pageUrl }
                )
            }
            return AnalyzedMedia(
                sourceUrl = sourceUrl,
                title = candidates.first().title.ifBlank { "Vídeo do Instagram" },
                extractor = "Instagram público",
                formats = formats,
                useBrowserSession = false,
                thumbnailUrl = candidates.firstNotNullOfOrNull { it.thumbnailUrl.takeIf(String::isNotBlank) }.orEmpty()
            )
        }
        return null
    }

    private fun isDirectVideoCandidate(candidate: BrowserMediaExtractor.Candidate): Boolean {
        val text = "${candidate.url} ${candidate.mimeType} ${candidate.subtitle}".lowercase(Locale.US)
        return candidate.mimeType.startsWith("video/", true) ||
            text.contains("video_url") ||
            text.contains("og:video") ||
            text.substringBefore('?').contains(".mp4") ||
            text.substringBefore('?').contains(".webm") ||
            text.substringBefore('?').contains(".mov")
    }

    private fun directExtension(candidate: BrowserMediaExtractor.Candidate): String {
        val path = runCatching { URI(candidate.url).path.orEmpty() }.getOrDefault("")
        return when {
            path.endsWith(".webm", true) || candidate.mimeType.contains("webm", true) -> "webm"
            path.endsWith(".mov", true) || candidate.mimeType.contains("quicktime", true) -> "mov"
            else -> "mp4"
        }
    }

    private fun buildFormatChoices(context: Context, formats: List<VideoFormat>, fallbackId: String?, fallbackExt: String?): List<FormatChoice> {
        val quality = PrivateBrowserStore.mediaQuality(context)
        val maxHeight = maxHeightFor(PrivateBrowserStore.mediaResolution(context))
        val bestSelector = when {
            maxHeight > 0 -> "bestvideo[height<=$maxHeight][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=$maxHeight]+bestaudio/best[height<=$maxHeight]/best"
            quality == PrivateBrowserStore.QUALITY_SMALL -> "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]/worst"
            else -> "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
        }
        val best = FormatChoice(
            id = "best",
            title = if (maxHeight > 0) "Melhor até ${maxHeight}p" else "Melhor disponível",
            detail = "Usa a melhor combinação de vídeo e áudio que o site disponibilizar",
            selector = bestSelector,
            outputExtension = "mp4"
        )
        val candidates = formats.asSequence()
            .filter { !it.formatId.isNullOrBlank() }
            .filter { !it.vcodec.isNullOrBlank() && !it.vcodec.equals("none", true) }
            .filter { it.height > 0 }
            .filter { maxHeight <= 0 || it.height <= maxHeight }
            .groupBy { it.height to normalizedFps(it.fps) }
            .mapNotNull { (_, group) -> group.maxByOrNull(::formatScore) }
            .sortedWith(compareByDescending<VideoFormat> { it.height }.thenByDescending { normalizedFps(it.fps) })
            .take(12)
            .map { format ->
                val height = format.height.coerceAtLeast(0)
                val fps = normalizedFps(format.fps)
                val ext = format.ext.orEmpty().lowercase(Locale.US).ifBlank { "mp4" }
                val output = if (ext == "mp4") "mp4" else "mkv"
                val size = format.fileSize.takeIf { it > 0L } ?: format.fileSizeApproximate
                val selector = buildString {
                    append("bestvideo[height<=").append(height).append("][ext=mp4]+bestaudio[ext=m4a]")
                    append("/bestvideo[height<=").append(height).append("]+bestaudio")
                    append("/best[height<=").append(height).append("]")
                    append('/').append(format.formatId)
                }
                val title = buildString {
                    append(height).append('p')
                    if (fps > 30) append(" • ").append(fps).append(" fps")
                }
                val detail = buildList {
                    add(ext.uppercase(Locale.US))
                    if (size > 0L) add(formatBytes(size))
                    format.vcodec?.takeIf { it.isNotBlank() && !it.equals("none", true) }?.let { add(shortCodec(it)) }
                    if (format.acodec.isNullOrBlank() || format.acodec.equals("none", true)) add("áudio combinado") else add("com áudio")
                }.joinToString(" • ")
                FormatChoice("$height-$fps", title, detail, selector, output, size)
            }
            .toList()
        val fallback = if (candidates.isEmpty() && !fallbackId.isNullOrBlank()) {
            listOf(FormatChoice("source", "Formato original", fallbackExt.orEmpty().uppercase(Locale.US).ifBlank { "VÍDEO" }, fallbackId, fallbackExt.orEmpty().ifBlank { "mp4" }))
        } else emptyList()
        return (listOf(best) + candidates + fallback).distinctBy { it.id }
    }

    private fun thumbnailUrlFromInfo(info: Any): String {
        val getter = info.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && (it.name.equals("getThumbnail", true) || it.name.equals("getThumbnailUrl", true))
        }
        val fromGetter = runCatching { getter?.invoke(info)?.toString().orEmpty() }.getOrDefault("")
        if (isValidHttpUrl(fromGetter)) return fromGetter
        val field = info.javaClass.fields.firstOrNull {
            it.name.equals("thumbnail", true) || it.name.equals("thumbnailUrl", true)
        }
        return runCatching { field?.get(info)?.toString().orEmpty() }
            .getOrDefault("")
            .takeIf(::isValidHttpUrl)
            .orEmpty()
    }

    private fun YoutubeDLRequest.applySiteOptions(context: Context, url: String, useBrowserSession: Boolean) {
        if (InstagramPublicAccess.isInstagramUrl(url)) {
            if (InstagramPublicAccess.isEmbedUrl(url)) {
                addOption("--force-generic-extractor")
                addOption("--referer", INSTAGRAM_REFERRER)
                addOption("--user-agent", BrowserWebViewConfigurator.currentUserAgent(context))
            } else if (useBrowserSession) {
                addOption("--extractor-args", "instagram:app_id=ios")
            }
        }
    }

    private fun buildCookiesFile(context: Context, url: String): File? {
        val cookieHeader = CookieManager.getInstance().getCookie(url).orEmpty()
        if (cookieHeader.isBlank()) return null
        val host = runCatching { URI(url).host.orEmpty().trim('.') }.getOrDefault("").ifBlank { return null }
        val file = File(context.cacheDir, "sv_cookies_${System.currentTimeMillis()}.txt")
        val domain = if (host == "instagram.com" || host.endsWith(".instagram.com")) ".instagram.com" else ".$host"
        val lines = mutableListOf("# Netscape HTTP Cookie File")
        cookieHeader.split(';').map { it.trim() }.filter { it.contains('=') }.forEach { pair ->
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=').trim()
            if (name.isNotBlank()) lines += listOf(domain, "TRUE", "/", "FALSE", "0", name, value).joinToString("\t")
        }
        if (lines.size <= 1) return null
        VaultCleanupRepository.protectTemporary(file)
        return try {
            file.writeText(lines.joinToString("\n"))
            file
        } catch (error: Throwable) {
            file.delete()
            VaultCleanupRepository.releaseTemporary(file)
            throw error
        }
    }

    private fun hasInstagramSession(url: String): Boolean = InstagramPublicAccess.hasAuthenticatedSession(
        CookieManager.getInstance().getCookie(url).orEmpty()
    )

    private fun moveToDestination(context: Context, source: File, destination: String): File {
        val directory = when (destination) {
            PrivateBrowserStore.DESTINATION_SECONDARY -> SecondaryVaultRepository.directory(context)
            PrivateBrowserStore.DESTINATION_TERTIARY -> TertiaryVaultRepository.directory(context)
            else -> VaultRepository.primaryDirectory(context)
        }
        PrivateStorageGuard.requireSpace(directory, source.length())
        val ext = source.extension.lowercase(Locale.US).ifBlank { "mp4" }
        val rawName = source.name.ifBlank { "Video_${System.currentTimeMillis()}.$ext" }
        val fileName = VaultMediaFormats.importFileName(rawName, VaultMediaFormats.mimeFor(ext, video = true), "VIDEO")
        val target = uniqueFile(directory, fileName)
        if (!source.renameTo(target)) {
            source.inputStream().buffered().use { input ->
                FileOutputStream(target).buffered().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            FileOutputStream(target, true).use { it.fd.sync() }
            require(target.length() == source.length()) { "A cópia baixada ficou incompleta" }
        }
        VaultMediaIndex.invalidate()
        return target
    }

    private fun uniqueFile(directory: File, requestedName: String): File {
        directory.mkdirs()
        val safe = VaultMediaFormats.sanitizeName(requestedName)
        val dot = safe.lastIndexOf('.')
        val fileStem = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var candidate = File(directory, safe)
        var index = 1
        while (candidate.exists()) candidate = File(directory, "${fileStem}_${index++}$extension")
        return candidate
    }

    private fun downloadableVideos(directory: File): List<File> = directory.walkTopDown()
        .filter(File::isFile)
        .filter { file ->
            val name = file.name.lowercase(Locale.US)
            !name.endsWith(".part") && !name.endsWith(".ytdl") && VaultMediaFormats.isVideoExtension(file.extension)
        }
        .sortedByDescending(File::length)
        .toList()

    private fun isValidHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun normalizedFps(value: Int): Int = if (value <= 0) 30 else value
    private fun formatScore(format: VideoFormat): Long {
        val size = format.fileSize.takeIf { it > 0L } ?: format.fileSizeApproximate
        val codecScore = when {
            format.vcodec.orEmpty().contains("avc", true) -> 40_000L
            format.vcodec.orEmpty().contains("h264", true) -> 40_000L
            format.vcodec.orEmpty().contains("hevc", true) -> 35_000L
            format.vcodec.orEmpty().contains("vp9", true) -> 25_000L
            else -> 10_000L
        }
        val audio = if (!format.acodec.isNullOrBlank() && !format.acodec.equals("none", true)) 20_000L else 0L
        return format.height.toLong() * 1_000_000L + normalizedFps(format.fps) * 1_000L + codecScore + audio + size.coerceAtLeast(0L) / 1_000_000L
    }

    private fun maxHeightFor(value: String): Int = when (value) {
        PrivateBrowserStore.RESOLUTION_2160 -> 2160
        PrivateBrowserStore.RESOLUTION_1440 -> 1440
        PrivateBrowserStore.RESOLUTION_1080 -> 1080
        PrivateBrowserStore.RESOLUTION_720 -> 720
        PrivateBrowserStore.RESOLUTION_480 -> 480
        else -> 0
    }

    private fun shortCodec(value: String): String = when {
        value.contains("avc", true) || value.contains("h264", true) -> "H.264"
        value.contains("hevc", true) || value.contains("h265", true) -> "HEVC"
        value.contains("vp9", true) -> "VP9"
        value.contains("av01", true) -> "AV1"
        else -> value.substringBefore('.').uppercase(Locale.US).take(12)
    }

    private fun progressText(line: String?, etaSeconds: Long): String {
        val clean = line.orEmpty()
            .replace(Regex("\\x1B\\[[0-?]*[ -/]*[@-~]"), "")
            .substringAfter("] ", line.orEmpty())
            .trim()
            .take(110)
        val eta = if (etaSeconds > 0) " • ${formatEta(etaSeconds)}" else ""
        return when {
            clean.contains("Merging", true) || clean.contains("Merger", true) -> "Unindo vídeo e áudio…"
            clean.contains("metadata", true) || clean.contains("thumbnail", true) -> "Finalizando mídia…"
            clean.isNotBlank() -> "$clean$eta"
            else -> "Baixando vídeo$eta"
        }
    }

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3600 -> "%dh %02dm".format(Locale.getDefault(), seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> "%dm %02ds".format(Locale.getDefault(), seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
        else String.format(Locale.getDefault(), "%.1f MB", mb)
    }

    private fun cleanError(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (message.contains("DRM", true) || message.contains("protected", true)) return "Esse vídeo parece protegido pelo site e não pode ser baixado pelo app."
        if (message.contains("login", true) || message.contains("cookies", true)) return "O site pediu sessão/login. Abra o site no Navegar, entre na sua conta e tente de novo."
        if (message.contains("403") || message.contains("404")) return "O site bloqueou ou não disponibilizou esse vídeo para download."
        if (message.contains("Unsupported URL", true)) return "Esse site/link ainda não é compatível com o baixador avançado."
        return message.ifBlank { "Não foi possível baixar esse vídeo" }.take(240)
    }

    private fun cleanInstagramError(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .replace(Regex("\\s+"), " ")
            .trim()
        if (message.contains("DRM", true) || message.contains("protected", true)) {
            return "Esse vídeo parece protegido pelo Instagram e não pode ser baixado pelo app."
        }
        if (message.contains("404") || message.contains("not available", true)) {
            return "O Instagram não disponibilizou essa mídia pela rota pública. Confira se o link ainda existe e se o reel é público."
        }
        return "Não foi possível obter o arquivo público desse reel. Tente novamente; login só é usado pelo app para mídia que não é pública."
    }

    private val SOCIAL_HOST_HINTS = setOf(
        "instagram.com", "youtube.com", "youtu.be", "facebook.com", "fb.watch", "tiktok.com", "x.com", "twitter.com", "vimeo.com"
    )

    private const val INSTAGRAM_REFERRER = "https://www.instagram.com/"
}
