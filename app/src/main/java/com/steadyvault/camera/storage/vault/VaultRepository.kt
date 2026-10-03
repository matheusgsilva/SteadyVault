package com.steadyvault.camera.storage.vault

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

object VaultRepository {
    data class MediaItem(
        val file: File,
        val name: String,
        val mime: String,
        val video: Boolean,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int
    )

    data class ImportedMedia(val item: MediaItem, val contentSha256: String)

    data class ReplacementResult(
        val file: File,
        val deferred: Boolean
    )

    data class PrivateCaptureFiles(
        val working: File,
        val final: File
    )

    data class MaintenanceReport(val cleanedFiles: Int)

    data class MetadataCacheStats(
        val persistentEntries: Int,
        val persistentBytes: Long,
        val memoryEntries: Int,
        val durationMemoryEntries: Int
    )

    private val processingPaths = mutableMapOf<String, Long>()
    private val playbackPaths = mutableMapOf<String, Int>()
    private val videoDurationCache = mutableMapOf<String, Pair<Long, Long>>()
    private val metadataCacheLock = Any()
    private val detailedItemCache = object : LinkedHashMap<String, MediaItem>(DETAIL_CACHE_LIMIT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?): Boolean =
            size > DETAIL_CACHE_LIMIT
    }
    @Volatile private var metadataCacheGeneration = 0L
    private const val METADATA_PREFS = "steadyvault_media_metadata"
    private const val DETAIL_CACHE_LIMIT = 64

    @Synchronized
    fun acquireForProcessing(file: File): Boolean {
        pruneStaleProcessingLocks()
        val path = normalizedPath(file)
        if (!file.isFile || processingPaths.containsKey(path)) return false
        processingPaths[path] = System.currentTimeMillis()
        return true
    }

    @Synchronized
    fun acquireForPlayback(file: File): Boolean {
        pruneStaleProcessingLocks()
        val path = normalizedPath(file)
        if (!file.isFile) return false
        playbackPaths[path] = (playbackPaths[path] ?: 0) + 1
        return true
    }

    @Synchronized
    fun releaseFromPlayback(file: File) {
        val path = normalizedPath(file)
        val remaining = (playbackPaths[path] ?: 0) - 1
        if (remaining > 0) playbackPaths[path] = remaining else playbackPaths.remove(path)
    }

    @Synchronized
    fun isBeingViewed(file: File): Boolean = playbackPaths.containsKey(normalizedPath(file))

    @Synchronized
    fun heartbeatProcessing(file: File) {
        val path = normalizedPath(file)
        if (processingPaths.containsKey(path)) {
            processingPaths[path] = System.currentTimeMillis()
        }
    }

    @Synchronized
    fun releaseFromProcessing(file: File) {
        processingPaths.remove(normalizedPath(file))
    }

    @Synchronized
    fun isBeingProcessed(file: File): Boolean {
        pruneStaleProcessingLocks()
        return processingPaths.containsKey(normalizedPath(file))
    }

    @Synchronized
    fun releaseStaleProcessingLocks() {
        pruneStaleProcessingLocks()
    }

    private fun pruneStaleProcessingLocks() {
        val now = System.currentTimeMillis()
        processingPaths.entries.removeAll { now - it.value > STALE_PROCESSING_LOCK_MS }
    }

    @Synchronized
    fun primaryDirectory(context: Context): File {
        return File(context.filesDir, "vaults/primary").apply {
            mkdirs()
        }
    }

    fun createRecordingFile(context: Context, profileLabel: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val safeProfile = profileLabel.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return uniqueFile(primaryDirectory(context), "SV_${stamp}_${safeProfile}.mp4").also {
            VaultMediaIndex.invalidate()
        }
    }

    fun createPhotoFile(context: Context, sequenceIndex: Int? = null): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val suffix = sequenceIndex?.takeIf { it > 0 }?.let { "_${it.toString().padStart(2, '0')}" }.orEmpty()
        return uniqueFile(primaryDirectory(context), "SV_PHOTO_${stamp}${suffix}.jpg").also {
            VaultMediaIndex.invalidate()
        }
    }



    @Synchronized
    fun commitPrivateCapture(files: PrivateCaptureFiles): File {
        require(files.working.isFile && files.working.length() > 0L) { "Captura temporária inválida" }
        require(files.working.parentFile?.canonicalFile == files.final.parentFile?.canonicalFile) {
            "A captura precisa permanecer no cofre selecionado"
        }
        val expectedBytes = files.working.length()
        if (!files.working.renameTo(files.final)) {
            try {
                files.working.inputStream().buffered().use { input ->
                    files.final.outputStream().buffered().use(input::copyTo)
                }
                check(files.final.length() == expectedBytes) { "A captura final ficou incompleta" }
                check(files.working.delete()) { "Não foi possível remover o arquivo temporário" }
            } catch (throwable: Throwable) {
                files.final.delete()
                throw throwable
            }
        }
        VaultMediaIndex.invalidate()
        return files.final
    }


    fun cleanupStalePrivateCaptures(context: Context, destination: File) {
        val directory = requirePrivateCaptureDirectory(context, destination)
        val now = System.currentTimeMillis()
        val cutoff = now - PRIVATE_CAPTURE_STALE_MS
        directory.listFiles { file ->
            file.isFile && file.name.startsWith(PRIVATE_CAPTURE_WORK_PREFIX) && file.lastModified() < cutoff
        }.orEmpty().forEach { working ->
            val finalName = working.name
                .removePrefix(PRIVATE_CAPTURE_WORK_PREFIX)
                .removeSuffix(PRIVATE_CAPTURE_PENDING_SUFFIX)
            val final = File(directory, finalName)
            val recoverableVideo =
                finalName.endsWith(".mp4", ignoreCase = true) &&
                    RecordingRecoveryRepository.hasUsableVideo(working)
            val recovered = recoverableVideo && runCatching {
                commitPrivateCapture(PrivateCaptureFiles(working, final))
            }.isSuccess
            if (
                !recovered &&
                now - working.lastModified() >= PRIVATE_CAPTURE_RETENTION_MS
            ) {
                working.delete()
            }
        }
    }

    fun createOptimizationWorkFile(context: Context, source: File): File {
        val fileStem = source.nameWithoutExtension.take(80).ifBlank { "video" }
        return uniqueFile(outputDirectoryFor(context, source), ".${fileStem}_processing.tmp")
    }

    @Synchronized
    fun replaceOriginalOrDefer(context: Context, original: File, optimized: File): ReplacementResult {
        require(original.isFile && optimized.isFile && optimized.length() > 0L) { "Arquivos inválidos para substituição" }
        return if (isBeingViewed(original)) {
            ReplacementResult(deferReplacement(context, original, optimized), deferred = true)
        } else {
            removeCachedMediaData(context, original)
            ReplacementResult(replaceOriginal(original, optimized), deferred = false)
        }
    }

    @Synchronized
    fun runStartupMaintenance(context: Context): MaintenanceReport {
        val directory = primaryDirectory(context)
        pruneStaleProcessingLocks()
        recoverInterruptedChanges(directory)
        listOf(
            directory,
            SecondaryVaultRepository.directory(context),
            TertiaryVaultRepository.directory(context)
        ).forEach { captureDirectory ->
            cleanupStalePrivateCaptures(context, captureDirectory)
        }

        var cleaned = cleanupStaleWorkFiles(directory)
        cleaned += cleanupLegacyImportReports(context)

        val pendingMarkers = directory.listFiles { file -> file.isFile && file.name.endsWith(PENDING_REPLACEMENT_SUFFIX) }
            .orEmpty()
        val referencedReadyFiles = pendingMarkers
            .mapNotNull { readPendingReplacement(it)["optimized"] }
            .toSet()

        pendingMarkers
            .forEach { marker ->
                val info = readPendingReplacement(marker)
                val originalName = info["original"].orEmpty()
                val optimizedName = info["optimized"].orEmpty()
                val original = File(directory, originalName)
                val optimized = File(directory, optimizedName)

                when {
                    originalName.isBlank() || optimizedName.isBlank() -> {
                        marker.delete()
                        cleaned++
                    }
                    !optimized.isFile || optimized.length() <= 0L -> {
                        optimized.delete()
                        marker.delete()
                        cleaned++
                    }
                    original.isFile && (isBeingViewed(original) || isBeingProcessed(original)) -> Unit
                    original.isFile -> {
                        runCatching { replaceOriginal(original, optimized) }
                            .onSuccess { marker.delete() }
                    }
                    else -> {
                        val visible = uniqueFile(directory, "${original.nameWithoutExtension.ifBlank { "video" }}_corrigido.mp4")
                        if (!optimized.renameTo(visible)) {
                            optimized.inputStream().buffered().use { input ->
                                visible.outputStream().buffered().use(input::copyTo)
                            }
                            optimized.delete()
                        }
                        marker.delete()
                        cleaned++
                    }
                }
            }

        cleaned += cleanupOrphanReadyFiles(directory, referencedReadyFiles)
        return MaintenanceReport(cleaned)
    }

    @Synchronized
    fun commitOptimizedFile(context: Context, working: File, source: File, label: String): File {
        require(working.isFile && working.length() > 0L) { "Arquivo otimizado inválido" }
        val fileStem = source.nameWithoutExtension.take(80).ifBlank { "video" }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "otimizado" }
        val finalFile = uniqueFile(outputDirectoryFor(context, source), "${fileStem}_${safeLabel}.mp4")
        val expectedBytes = working.length()
        if (!working.renameTo(finalFile)) {
            try {
                working.inputStream().buffered().use { input -> finalFile.outputStream().buffered().use(input::copyTo) }
                check(finalFile.length() == expectedBytes) { "A cópia otimizada ficou incompleta" }
                check(working.delete()) { "Não foi possível remover o arquivo temporário" }
            } catch (t: Throwable) {
                finalFile.delete()
                throw t
            }
        }
        VaultMediaIndex.invalidate()
        return finalFile
    }

    @Synchronized
    fun replaceOriginal(original: File, optimized: File): File {
        require(original.isFile && optimized.isFile && optimized.length() > 0L) { "Arquivos inválidos para substituição" }
        val expectedBytes = optimized.length()
        val backup = File(original.parentFile, original.name + ".backup")
        if (backup.exists()) backup.delete()
        check(original.renameTo(backup)) { "Não foi possível preservar o original" }
        return try {
            if (!optimized.renameTo(original)) {
                optimized.inputStream().buffered().use { input -> original.outputStream().buffered().use(input::copyTo) }
                check(original.length() == expectedBytes) { "A substituição do original ficou incompleta" }
                check(optimized.delete()) { "Não foi possível remover o arquivo temporário" }
            }
            check(original.length() == expectedBytes) { "O arquivo final não corresponde à saída validada" }
            backup.delete()
            VaultMediaIndex.invalidate()
            original
        } catch (t: Throwable) {
            original.delete()
            backup.renameTo(original)
            throw t
        }
    }

    @Synchronized
    fun list(context: Context): List<MediaItem> {
        VaultMediaIndex.reconcile(context, primaryDirectory(context))
        return VaultMediaIndex.listAll(context)
    }

    fun listPage(context: Context, offset: Int, limit: Int): List<MediaItem> {
        VaultMediaIndex.reconcile(context, primaryDirectory(context))
        return VaultMediaIndex.listPage(context, offset, limit)
    }


    fun importFromUri(context: Context, uri: Uri, onProgress: ((Long, Long) -> Unit)? = null): MediaItem {
        val resolver = context.contentResolver
        var displayName: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) displayName = cursor.getString(0)
            }
        }
        return importFromUri(context, uri, displayName, runCatching { resolver.getType(uri).orEmpty() }.getOrDefault(""), onProgress)
    }

    fun importFromUri(context: Context, uri: Uri, displayName: String?, mime: String, onProgress: ((Long, Long) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): MediaItem =
        importFromUriVerified(context, uri, displayName, mime, onProgress, shouldCancel).item

    fun importFromUriVerified(context: Context, uri: Uri, displayName: String?, mime: String, onProgress: ((Long, Long) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): ImportedMedia {
        check(!shouldCancel()) { "Importação cancelada" }
        val requestedName = VaultMediaFormats.importFileName(displayName, mime, "Importado")
        require(VaultMediaFormats.isSupported(requestedName, mime)) { "Formato de mídia não compatível" }
        val target = uniqueFile(primaryDirectory(context), requestedName)
        try {
            val copy = PrivateMediaFileWriter.copyFromUri(context, uri, target, onProgress, shouldCancel)
            check(!shouldCancel()) { "Importação cancelada" }
            val item = readItem(target, fast = true) ?: throw IllegalStateException("Formato de mídia não reconhecido")
            check(!shouldCancel()) { "Importação cancelada" }
            VaultMediaIndex.update(context, item)
            check(!shouldCancel()) { "Importação cancelada" }
            return ImportedMedia(item, copy.sha256)
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    fun exportToGallery(context: Context, item: MediaItem): Uri = PublicMediaExporter.export(context, item, if (item.video) "Movies/SteadyVault" else "Pictures/SteadyVault")

    @Synchronized
    fun delete(context: Context, item: MediaItem): Boolean {
        if (!isInsideVault(context, item.file) || isBeingProcessed(item.file) || isBeingViewed(item.file)) return false
        removeCachedMediaData(context, item.file)
        val deleted = item.file.delete()
        if (deleted) {
            VaultAlbumStore.removeMetadata(context, item.file)
            VaultMediaIndex.remove(context, item.file)
        }
        return deleted
    }

    fun removeCachedMediaData(context: Context, file: File) {
        MediaThumbnailRepository.remove(context, file)
        VideoFilmstripCache.remove(context, file)
        VaultMediaIndex.invalidate()
        val path = normalizedPath(file)
        synchronized(metadataCacheLock) {
            context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE).edit()
                .remove(metadataKey(file))
                .apply()
            synchronized(videoDurationCache) { videoDurationCache.remove(path) }
            synchronized(detailedItemCache) {
                detailedItemCache.entries.removeAll { normalizedPath(it.value.file) == path }
            }
        }
    }

    fun isInsideVault(context: Context, file: File): Boolean = isInsideDirectory(file, primaryDirectory(context))

    fun isInsideKnownVault(context: Context, file: File): Boolean =
        isInsideDirectory(file, primaryDirectory(context)) ||
            isInsideDirectory(file, SecondaryVaultRepository.directory(context)) ||
            isInsideDirectory(file, TertiaryVaultRepository.directory(context))

    private fun outputDirectoryFor(context: Context, source: File): File =
        source.parentFile?.takeIf { source.isFile && isInsideKnownVault(context, source) && it.isDirectory }
            ?: primaryDirectory(context)


    private fun requirePrivateCaptureDirectory(context: Context, destination: File): File {
        val requested = destination.canonicalFile
        val allowed = listOf(
            primaryDirectory(context).canonicalFile,
            SecondaryVaultRepository.directory(context).canonicalFile,
            TertiaryVaultRepository.directory(context).canonicalFile
        )
        require(allowed.any { it == requested }) { "Destino de captura inválido" }
        return requested
    }

    private fun isInsideDirectory(file: File, directory: File): Boolean = runCatching {
        val root = directory.canonicalFile
        val candidate = file.canonicalFile
        candidate.path.startsWith(root.path + File.separator) && candidate.isFile
    }.getOrDefault(false)

    @Synchronized
    fun findByPath(context: Context, path: String): MediaItem? {
        val file = File(path)
        if (!isInsideVault(context, file)) return null
        return readItem(file)
    }

    @Synchronized
    fun usedBytes(context: Context): Long {
        VaultMediaIndex.reconcile(context, primaryDirectory(context))
        return VaultMediaIndex.summary(context).bytes
    }

    fun formatBytes(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        return when {
            safe >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.2f GB", safe / (1024.0 * 1024.0 * 1024.0))
            safe >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", safe / (1024.0 * 1024.0))
            safe >= 1024L -> String.format(Locale.getDefault(), "%.1f KB", safe / 1024.0)
            else -> "$safe B"
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    fun mediaDetails(item: MediaItem): String {
        val type = if (item.video) "Vídeo" else "Imagem"
        val extension = item.file.extension.uppercase(Locale.US).ifBlank { "Desconhecido" }
        val resolution = if (item.width > 0 && item.height > 0) "${item.width} × ${item.height} pixels" else "Não informada"
        val modified = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm:ss", Locale.getDefault()).format(Date(item.modifiedAt))
        return buildString {
            append("Nome\n${item.name}\n\n")
            append("Tipo\n$type • $extension\n\n")
            append("Resolução\n$resolution\n\n")
            if (item.video) append("Duração\n${item.durationMs.takeIf { it > 0L }?.let(::formatDuration) ?: "Não informada"}\n\n")
            append("Tamanho\n${formatBytes(item.sizeBytes)}\n\n")
            append("Modificado em\n$modified")
        }
    }

    fun thumbnailDurationLabel(item: MediaItem): String {
        if (!item.video) return ""
        val pathKey = normalizedPath(item.file)
        val cachedDuration = synchronized(videoDurationCache) {
            videoDurationCache[pathKey]
                ?.takeIf { it.first == item.file.length() }
                ?.second
                ?: 0L
        }
        return maxOf(item.durationMs, cachedDuration, 0L).takeIf { it > 0L }?.let(::formatDuration).orEmpty()
    }

    fun readItem(file: File, fast: Boolean = false): MediaItem? {
        if (!file.isFile || file.length() <= 0L) return null
        val extension = file.extension.lowercase(Locale.US)
        val video = VaultMediaFormats.isVideoExtension(extension)
        val image = VaultMediaFormats.isImageExtension(extension)
        if (!video && !image) return null
        var duration = 0L
        var width = 0
        var height = 0
        var rotationDegrees = 0
        if (video) {
            val pathKey = normalizedPath(file)
            val length = file.length()
            synchronized(videoDurationCache) {
                videoDurationCache[pathKey]?.takeIf { it.first == length }?.let { duration = it.second }
            }
        }
        if (video && !fast && !isBeingProcessed(file)) {
            val pathKey = normalizedPath(file)
            val length = file.length()
            var encodedWidth = 0
            var encodedHeight = 0

            // Para a grade, MediaExtractor lê duração/resolução direto do contêiner e
            // evita abrir decoder. Isso é muito mais rápido para centenas/milhares de vídeos.
            val extractor = MediaExtractor()
            runCatching {
                extractor.setDataSource(file.absolutePath)
                for (trackIndex in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(trackIndex)
                    val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (!trackMime.startsWith("video/")) continue
                    val detectedDurationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
                    if (detectedDurationUs > 0L) duration = detectedDurationUs / 1_000L
                    if (format.containsKey(MediaFormat.KEY_WIDTH)) encodedWidth = format.getInteger(MediaFormat.KEY_WIDTH).coerceAtLeast(0)
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) encodedHeight = format.getInteger(MediaFormat.KEY_HEIGHT).coerceAtLeast(0)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotationDegrees = ((format.getInteger(MediaFormat.KEY_ROTATION) % 360) + 360) % 360
                    }
                    break
                }
            }
            runCatching { extractor.release() }

            // Alguns contêineres/provedores não expõem todos os campos no MediaExtractor.
            // Só nesses casos usamos o retriever, evitando o custo dele na maioria dos vídeos.
            if (duration <= 0L || encodedWidth <= 0 || encodedHeight <= 0) {
                val retriever = MediaMetadataRetriever()
                runCatching {
                    retriever.setDataSource(file.absolutePath)
                    if (duration <= 0L) duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    if (encodedWidth <= 0) encodedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    if (encodedHeight <= 0) encodedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: rotationDegrees
                }
                runCatching { retriever.release() }
            }

            if (duration > 0L) synchronized(videoDurationCache) { videoDurationCache[pathKey] = length to duration }
            if (rotationDegrees == 90 || rotationDegrees == 270) {
                width = encodedHeight
                height = encodedWidth
            } else {
                width = encodedWidth
                height = encodedHeight
            }
        }
        else if (image && !fast) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                width = options.outWidth.coerceAtLeast(0)
                height = options.outHeight.coerceAtLeast(0)
            }
        }
        val mime = VaultMediaFormats.mimeFor(extension, video)
        return MediaItem(file, file.name, mime, video, file.length(), file.lastModified(), duration, width, height, rotationDegrees)
    }

    fun applyCachedMetadata(context: Context, item: MediaItem): MediaItem {
        val raw = context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
            .getString(metadataKey(item.file), null) ?: return item
        val values = raw.split('|')
        if (values.size != 6 || values[0].toLongOrNull() != item.sizeBytes || values[1].toLongOrNull() != item.modifiedAt) return item
        return item.copy(
            durationMs = values[2].toLongOrNull()?.coerceAtLeast(0L) ?: item.durationMs,
            width = values[3].toIntOrNull()?.coerceAtLeast(0) ?: item.width,
            height = values[4].toIntOrNull()?.coerceAtLeast(0) ?: item.height,
            rotationDegrees = values[5].toIntOrNull()?.coerceAtLeast(0) ?: item.rotationDegrees
        )
    }

    /** Retorna detalhes já prontos sem abrir novamente a mídia. */
    fun cachedMediaDetails(context: Context, item: MediaItem): MediaItem? {
        if (hasCompleteMetadata(item)) return item
        val key = detailCacheKey(item.file)
        synchronized(detailedItemCache) {
            detailedItemCache[key]?.let { return it }
        }
        val generation = metadataCacheGeneration
        val persisted = applyCachedMetadata(context, item)
        if (generation != metadataCacheGeneration || !hasCompleteMetadata(persisted)) return null
        rememberDetailedItem(persisted, generation)
        return persisted
    }

    /**
     * Lê detalhes somente quando ainda não estão em memória ou no cache persistente.
     * O resultado é pequeno e limitado; o arquivo de foto/vídeo nunca é copiado para o cache.
     */
    fun loadMediaDetails(context: Context, item: MediaItem): MediaItem {
        cachedMediaDetails(context, item)?.let {
            updateMediaIndex(context, it)
            return it
        }
        // Metadados de vídeo podem abrir um decoder pelo MediaMetadataRetriever. Durante
        // a gravação preserve todo recurso do codec/ISP para Camera2 -> MediaCodec.
        if (VaultStartupCoordinator.isCapturePriorityActive(context)) return item
        val generation = metadataCacheGeneration
        val detailed = readItem(item.file, fast = false) ?: item
        persistMetadata(context, detailed, generation)
        return detailed
    }

    /** Completa metadados depois da primeira renderização e persiste o resultado. */
    fun enrichVideoMetadata(context: Context, items: List<MediaItem>): List<MediaItem> {
        if (VaultStartupCoordinator.isCapturePriorityActive(context)) return items
        val generation = metadataCacheGeneration
        val enriched = items.map { item ->
            val needsMetadata = item.video && (item.durationMs <= 0L || item.width <= 0 || item.height <= 0)
            val detailed = if (needsMetadata && !isBeingProcessed(item.file)) readItem(item.file, fast = false) ?: item else item
            detailed
        }
        synchronized(metadataCacheLock) {
            if (generation == metadataCacheGeneration) {
                val editor = context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE).edit()
                enriched.filter(::hasUsefulMetadata).forEach { detailed ->
                    editor.putString(metadataKey(detailed.file), encodedMetadata(detailed))
                    rememberDetailedItem(detailed, generation)
                }
                editor.apply()
            }
        }
        if (generation == metadataCacheGeneration) {
            enriched.filter(::hasUsefulMetadata).forEach { updateMediaIndex(context, it) }
        }
        return enriched
    }

    fun metadataCacheStats(context: Context): MetadataCacheStats {
        val preferences = context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)
        val entries = runCatching { preferences.all.keys.count { it.startsWith("media_") } }.getOrDefault(0)
        val bytes = runCatching { metadataPreferencesFile(context).length().coerceAtLeast(0L) }.getOrDefault(0L)
        val memoryEntries = synchronized(detailedItemCache) { detailedItemCache.size }
        val durationMemoryEntries = synchronized(videoDurationCache) { videoDurationCache.size }
        return MetadataCacheStats(entries, bytes, memoryEntries, durationMemoryEntries)
    }

    fun clearMetadataCache(context: Context): MetadataCacheStats {
        val previous = metadataCacheStats(context)
        synchronized(metadataCacheLock) {
            metadataCacheGeneration++
            context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            synchronized(videoDurationCache) { videoDurationCache.clear() }
            synchronized(detailedItemCache) { detailedItemCache.clear() }
        }
        VaultMediaIndex.clearDetails(context)
        return previous
    }

    private fun persistMetadata(context: Context, item: MediaItem, generation: Long) {
        if (!hasUsefulMetadata(item)) return
        synchronized(metadataCacheLock) {
            if (generation != metadataCacheGeneration) return
            context.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE).edit()
                .putString(metadataKey(item.file), encodedMetadata(item))
                .apply()
            rememberDetailedItem(item, generation)
        }
        updateMediaIndex(context, item)
    }

    private fun updateMediaIndex(context: Context, item: MediaItem) {
        when {
            isInsideDirectory(item.file, primaryDirectory(context)) -> VaultMediaIndex.update(context, item)
            isInsideDirectory(item.file, SecondaryVaultRepository.directory(context)) -> PrivateVaultMediaIndex.update(context, VaultAreaId.SECONDARY, item)
            isInsideDirectory(item.file, TertiaryVaultRepository.directory(context)) -> PrivateVaultMediaIndex.update(context, VaultAreaId.TERTIARY, item)
        }
    }

    private fun rememberDetailedItem(item: MediaItem, generation: Long) {
        if (generation != metadataCacheGeneration || !hasCompleteMetadata(item)) return
        synchronized(detailedItemCache) { detailedItemCache[detailCacheKey(item.file)] = item }
    }

    private fun hasUsefulMetadata(item: MediaItem): Boolean =
        item.width > 0 || item.height > 0 || (item.video && item.durationMs > 0L)

    private fun hasCompleteMetadata(item: MediaItem): Boolean =
        item.width > 0 && item.height > 0 && (!item.video || item.durationMs > 0L)

    private fun encodedMetadata(item: MediaItem): String =
        "${item.sizeBytes}|${item.modifiedAt}|${item.durationMs}|${item.width}|${item.height}|${item.rotationDegrees}"

    private fun detailCacheKey(file: File): String =
        "${normalizedPath(file)}|${file.length()}|${file.lastModified()}"

    private fun metadataPreferencesFile(context: Context): File =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "$METADATA_PREFS.xml")

    private fun metadataKey(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedPath(file).toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "media_$digest"
    }


    private fun deferReplacement(context: Context, original: File, optimized: File): File {
        val directory = outputDirectoryFor(context, original)
        val fileStem = original.nameWithoutExtension.take(80).ifBlank { "video" }
        val ready = uniqueFile(directory, ".${fileStem}_${System.currentTimeMillis()}$READY_REPLACEMENT_SUFFIX")
        val expectedBytes = optimized.length()
        if (!optimized.renameTo(ready)) {
            optimized.inputStream().buffered().use { input -> ready.outputStream().buffered().use(input::copyTo) }
            check(ready.length() == expectedBytes) { "A cópia corrigida ficou incompleta" }
            check(optimized.delete()) { "Não foi possível remover o arquivo temporário" }
        }
        val marker = uniqueFile(directory, ".${fileStem}_${System.currentTimeMillis()}$PENDING_REPLACEMENT_SUFFIX")
        marker.writeText(
            buildString {
                append("original=").append(original.name).append('\n')
                append("optimized=").append(ready.name).append('\n')
                append("createdAt=").append(System.currentTimeMillis()).append('\n')
            }
        )
        return original
    }

    private fun readPendingReplacement(marker: File): Map<String, String> = runCatching {
        marker.readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun cleanupLegacyImportReports(context: Context): Int {
        val dir = File(context.filesDir, "ImportReports")
        if (!dir.exists()) return 0
        var cleaned = 0
        dir.walkBottomUp().forEach { file ->
            if (file != dir && file.delete()) cleaned++
        }
        if (dir.delete()) cleaned++
        return cleaned
    }

    private fun cleanupStaleWorkFiles(directory: File): Int {
        val now = System.currentTimeMillis()
        var cleaned = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.endsWith("_processing.tmp") && now - file.lastModified() > STALE_WORK_FILE_MS) {
                if (file.delete()) cleaned++
            }
        }
        return cleaned
    }

    private fun cleanupOrphanReadyFiles(directory: File, referencedNames: Set<String>): Int {
        val now = System.currentTimeMillis()
        var cleaned = 0
        directory.listFiles().orEmpty().forEach { file ->
            if (file.isFile &&
                file.name.endsWith(READY_REPLACEMENT_SUFFIX) &&
                file.name !in referencedNames &&
                now - file.lastModified() > STALE_WORK_FILE_MS
            ) {
                if (file.delete()) cleaned++
            }
        }
        return cleaned
    }

    private fun recoverInterruptedChanges(directory: File) {
        val now = System.currentTimeMillis()
        directory.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".backup") -> {
                    val original = File(directory, file.name.removeSuffix(".backup"))
                    if (original.exists()) file.delete() else file.renameTo(original)
                }
                file.name.endsWith("_processing.tmp") && now - file.lastModified() > STALE_WORK_FILE_MS -> file.delete()
            }
        }
    }

    @Synchronized
    private fun uniqueFile(directory: File, requestedName: String): File {
        val sanitized = sanitizeName(requestedName)
        val dot = sanitized.lastIndexOf('.')
        val fileStem = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val ext = if (dot > 0) sanitized.substring(dot) else ""
        var candidate = File(directory, sanitized)
        var index = 1
        while (candidate.exists()) candidate = File(directory, "${fileStem}_$index$ext").also { index++ }
        return candidate
    }


    private fun normalizedPath(file: File): String = file.absoluteFile.normalize().path

    private fun sanitizeName(value: String): String = VaultMediaFormats.sanitizeName(value)

    private const val STALE_WORK_FILE_MS = 12L * 60L * 60L * 1000L
    private const val STALE_PROCESSING_LOCK_MS = 5L * 60L * 1000L
    private const val PRIVATE_CAPTURE_STALE_MS = 60L * 60L * 1000L
    private const val PRIVATE_CAPTURE_RETENTION_MS =
        7L * 24L * 60L * 60L * 1_000L
    private const val PRIVATE_CAPTURE_WORK_PREFIX = ".SV_CAPTURE_"
    private const val PRIVATE_CAPTURE_PENDING_SUFFIX = ".pending"
    private const val PENDING_REPLACEMENT_SUFFIX = ".replace_pending"
    private const val READY_REPLACEMENT_SUFFIX = ".replace_ready"
}
