package com.steadyvault.camera.ui.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.URLUtil
import com.steadyvault.camera.core.storage.PrivateStorageGuard
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultCleanupRepository
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import com.steadyvault.camera.storage.vault.VaultMediaIndex
import com.steadyvault.camera.storage.vault.VaultRepository
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

internal object BrowserVaultDownloader {
    private const val COPY_BUFFER_SIZE = 1024 * 1024
    private const val SPACE_CHECK_INTERVAL_BYTES = 16L * 1024L * 1024L
    private const val MAX_REDIRECTS = 5

    data class Result(
        val file: File,
        val destinationLabel: String,
        val bytes: Long
    )

    fun download(
        context: Context,
        url: String,
        userAgent: String,
        cookie: String,
        contentDisposition: String,
        mimeType: String,
        destination: String,
        referrer: String,
        quality: String,
        resolution: String,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: (percent: Int, message: String) -> Unit
    ): Result {
        require(url.startsWith("http://", true) || url.startsWith("https://", true)) {
            "Esse tipo de link não permite download direto pelo WebView"
        }
        val app = context.applicationContext
        enforceNetworkPolicy(app)
        val effectiveUserAgent = userAgent.ifBlank { BrowserWebViewConfigurator.currentUserAgent(app) }
        val connection = openConnection(url, effectiveUserAgent, cookie, referrer)
        try {
            val code = connection.responseCode
            require(code in 200..299) { if (code == 401 || code == 403) "O servidor bloqueou esse link direto" else "Servidor retornou HTTP $code" }
            val responseMime = connection.contentType?.substringBefore(';')?.trim().orEmpty().ifBlank { mimeType }
            val effectiveMime = when {
                responseMime.startsWith("image/", true) || responseMime.startsWith("video/", true) -> responseMime
                isLikelyVideoHost(connection.url.toString()) -> "video/mp4"
                isLikelyImageHost(connection.url.toString()) -> "image/jpeg"
                else -> responseMime
            }
            val responseDisposition = connection.getHeaderField("Content-Disposition").orEmpty().ifBlank { contentDisposition }
            val guessed = URLUtil.guessFileName(connection.url.toString(), responseDisposition, effectiveMime)
            val looksLikeMedia = effectiveMime.startsWith("image/", true) ||
                effectiveMime.startsWith("video/", true) ||
                VaultMediaFormats.isSupported(guessed, effectiveMime) ||
                isLikelyMediaUrl(connection.url.toString(), effectiveMime)
            require(looksLikeMedia && !effectiveMime.startsWith("text/html", true)) {
                "A página não entregou um arquivo direto de foto ou vídeo"
            }
            val fileName = VaultMediaFormats.importFileName(guessed, effectiveMime, "WEB")
            require(VaultMediaFormats.isSupported(fileName, effectiveMime)) {
                "O arquivo baixado não parece ser foto ou vídeo compatível"
            }
            val directory = directoryFor(app, destination)
            val target = uniqueFile(directory, fileName)
            val temp = uniqueFile(directory, ".${target.nameWithoutExtension}_${System.currentTimeMillis()}.download")
            VaultCleanupRepository.protectTemporary(temp)
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            if (total > 0L && PrivateBrowserStore.verifyFreeSpace(app)) PrivateStorageGuard.requireSpace(directory, total)
            var copied = 0L
            val qualityText = PrivateBrowserStore.qualityLabel(quality)
            val resolutionText = PrivateBrowserStore.resolutionLabel(resolution)
            onProgress(-1, "Conectando… $qualityText • $resolutionText")
            try {
                connection.inputStream.buffered().use { input ->
                    FileOutputStream(temp).buffered().use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var lastPercent = -1
                        var nextSpaceCheck = SPACE_CHECK_INTERVAL_BYTES
                        while (true) {
                            check(!cancelled()) { "Download cancelado" }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read.toLong()
                            if (copied >= nextSpaceCheck && PrivateBrowserStore.verifyFreeSpace(app)) {
                                val remaining = if (total > copied) total - copied else SPACE_CHECK_INTERVAL_BYTES
                                PrivateStorageGuard.requireSpace(directory, remaining)
                                nextSpaceCheck = copied + SPACE_CHECK_INTERVAL_BYTES
                            }
                            if (total > 0L) {
                                val percent = ((copied * 100L) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent && (percent == 100 || percent - lastPercent >= 2)) {
                                    lastPercent = percent
                                    onProgress(percent, "Baixando ${formatBytes(copied)} de ${formatBytes(total)}")
                                }
                            } else if (copied % (8L * 1024L * 1024L) < COPY_BUFFER_SIZE) {
                                onProgress(-1, "Baixando ${formatBytes(copied)}…")
                            }
                        }
                        output.flush()
                    }
                }
                FileOutputStream(temp, true).use { it.fd.sync() }
                require(temp.length() > 0L) { "Download vazio" }
                if (!temp.renameTo(target)) {
                    temp.inputStream().buffered().use { input -> target.outputStream().buffered().use(input::copyTo) }
                    require(target.length() == temp.length()) { "A cópia baixada ficou incompleta" }
                    temp.delete()
                }
                VaultRepository.readItem(target, fast = true)
                    ?: throw IllegalStateException("A mídia baixada não foi reconhecida pelo cofre")
                VaultMediaIndex.invalidate()
                onProgress(100, "Salvo em ${PrivateBrowserStore.destinationLabel(destination)}")
                return Result(target, PrivateBrowserStore.destinationLabel(destination), target.length())
            } catch (throwable: Throwable) {
                temp.delete()
                target.delete()
                throw throwable
            } finally {
                VaultCleanupRepository.releaseTemporary(temp)
            }
        } finally {
            connection.disconnect()
        }
    }



    private fun enforceNetworkPolicy(context: Context) {
        if (!PrivateBrowserStore.wifiOnly(context)) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let(manager::getNetworkCapabilities)
        require(capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            "Downloads estão configurados para usar somente Wi‑Fi"
        }
    }

    fun isLikelyMediaUrl(url: String?, mimeType: String = ""): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.US)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        if (normalizedMime in NON_MEDIA_MIME_TYPES || normalizedMime.startsWith("text/") || normalizedMime.contains("javascript")) return false
        if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) return true
        val clean = lower.substringBefore('#').substringBefore('?')
        val extension = clean.substringAfterLast('.', missingDelimiterValue = "")
        if (extension in NON_MEDIA_EXTENSIONS) return false
        if (extension.isNotBlank() && VaultMediaFormats.isSupported("media.$extension", mimeType)) return true
        return MEDIA_HOST_HINTS.any { lower.contains(it) } || MEDIA_QUERY_HINTS.any { lower.contains(it) }
    }

    fun mimeHintForUrl(url: String): String {
        val lower = url.lowercase(Locale.US)
        val clean = lower.substringBefore('#').substringBefore('?')
        val extension = clean.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            extension in VIDEO_EXTENSIONS ||
                lower.contains("googlevideo.com/videoplayback") ||
                lower.contains("mime=video") ||
                lower.contains("mime%3dvideo") -> "video/*"
            extension in IMAGE_EXTENSIONS ||
                lower.contains("mime=image") ||
                lower.contains("mime%3dimage") -> "image/*"
            else -> ""
        }
    }

    fun isLikelyVideoUrl(url: String): Boolean = mimeHintForUrl(url).startsWith("video/")

    fun isLikelyStreamUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.US).orEmpty()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val clean = lower.substringBefore('#').substringBefore('?')
        return clean.endsWith(".m3u8") ||
            clean.endsWith(".mpd") ||
            lower.contains("manifest.m3u8") ||
            lower.contains("manifest.mpd") ||
            lower.contains("application/x-mpegurl") ||
            lower.contains("application/dash+xml")
    }

    fun displayNameForUrl(url: String): String {
        val clean = url.substringBefore('#').substringBefore('?').trimEnd('/')
        val last = clean.substringAfterLast('/', missingDelimiterValue = "").takeIf { it.isNotBlank() } ?: "mídia"
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).take(48)
    }

    fun hostForUrl(url: String): String = runCatching { URL(url).host }
        .getOrNull()
        .orEmpty()
        .ifBlank { url.take(80) }

    private fun openConnection(url: String, userAgent: String, cookie: String, referrer: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { index ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "video/*,image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (!location.isNullOrBlank() && index < MAX_REDIRECTS) {
                    current = URL(current, location)
                    return@repeat
                }
            }
            return connection
        }
        throw IllegalStateException("Redirecionamentos demais no download")
    }

    private fun directoryFor(context: Context, destination: String): File = when (destination) {
        PrivateBrowserStore.DESTINATION_SECONDARY -> SecondaryVaultRepository.directory(context)
        PrivateBrowserStore.DESTINATION_TERTIARY -> TertiaryVaultRepository.directory(context)
        else -> VaultRepository.primaryDirectory(context)
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

    private fun formatBytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.getDefault(), "%.2f GB", mb / 1024.0)
        else String.format(Locale.getDefault(), "%.1f MB", mb)
    }

    private fun isLikelyVideoHost(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("cdninstagram.com") ||
            lower.contains("fbcdn.net") ||
            lower.contains("video") ||
            lower.contains("mime=video") ||
            lower.contains("mime%3dvideo")
    }

    private fun isLikelyImageHost(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return lower.contains("cdninstagram.com") ||
            lower.contains("fbcdn.net") ||
            lower.contains("image") ||
            lower.contains("mime=image") ||
            lower.contains("mime%3dimage")
    }

    private val MEDIA_HOST_HINTS = setOf(
        "googlevideo.com/videoplayback"
    )
    private val MEDIA_QUERY_HINTS = setOf(
        "mime=video", "mime%3dvideo", "video/mp4", "video%2fmp4"
    )
    private val NON_MEDIA_EXTENSIONS = setOf(
        "js", "mjs", "css", "json", "map", "wasm", "html", "htm", "xml", "txt", "woff", "woff2", "ttf", "otf"
    )
    private val NON_MEDIA_MIME_TYPES = setOf(
        "application/javascript", "application/x-javascript", "application/json", "application/ld+json", "application/wasm"
    )
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif")

}
