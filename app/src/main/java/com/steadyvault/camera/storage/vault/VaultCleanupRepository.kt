package com.steadyvault.camera.storage.vault

import android.content.Context
import com.steadyvault.camera.core.state.CaptureStateStore
import java.io.File
import java.util.Collections

/**
 * Limpeza segura: remove cache, miniaturas e metadados órfãos sem apagar mídias salvas.
 */
object VaultCleanupRepository {
    private val activeTemporaryPaths = Collections.synchronizedSet(mutableSetOf<String>())

    data class CacheSnapshot(
        val thumbnailFiles: Int,
        val thumbnailDiskBytes: Long,
        val thumbnailMemoryBytes: Long,
        val thumbnailMemoryCaches: Int,
        val metadataEntries: Int,
        val metadataDiskBytes: Long,
        val metadataMemoryEntries: Int,
        val durationMemoryEntries: Int,
        val runtimeFiles: Int,
        val runtimeBytes: Long
    ) {
        val totalDiskBytes: Long = thumbnailDiskBytes + metadataDiskBytes + runtimeBytes

        fun summary(): String = buildString {
            append("ARMAZENAMENTO\n")
            append("Miniaturas: ").append(VaultRepository.formatBytes(thumbnailDiskBytes))
                .append(" • ").append(thumbnailFiles).append(" arquivo(s)\n")
            append("Detalhes e metadados: ").append(VaultRepository.formatBytes(metadataDiskBytes))
                .append(" • ").append(metadataEntries).append(" registro(s)\n")
            append("Temporários: ").append(VaultRepository.formatBytes(runtimeBytes))
                .append(" • ").append(runtimeFiles).append(" arquivo(s)\n")
            append("Total em disco: ").append(VaultRepository.formatBytes(totalDiskBytes)).append("\n\n")
            append("MEMÓRIA ENQUANTO O APP ESTÁ ABERTO\n")
            append("Miniaturas: ").append(VaultRepository.formatBytes(thumbnailMemoryBytes))
                .append(" • ").append(thumbnailMemoryCaches).append(" galeria(s) em memória\n")
            append("Detalhes prontos: ").append(metadataMemoryEntries).append(" item(ns)\n")
            append("Durações rápidas: ").append(durationMemoryEntries).append(" vídeo(s)")
        }
    }

    data class CleanupReport(
        val thumbnailFiles: Int,
        val thumbnailBytes: Long,
        val thumbnailMemoryBytes: Long,
        val metadataEntries: Int,
        val metadataBytes: Long,
        val metadataMemoryEntries: Int,
        val durationMemoryEntries: Int,
        val cacheFiles: Int,
        val cacheBytes: Long,
        val runtimeCacheProtected: Boolean,
        val mainAlbumMetadata: Int,
        val trashMetadata: Int,
        val startupCleanedFiles: Int
    ) {
        fun summary(): String = buildString {
            append("Limpeza concluída sem apagar mídias salvas.\n\n")
            append("Miniaturas removidas: ").append(thumbnailFiles).append(" (").append(VaultRepository.formatBytes(thumbnailBytes)).append(")\n")
            append("Miniaturas liberadas da memória: ").append(VaultRepository.formatBytes(thumbnailMemoryBytes)).append("\n")
            append("Detalhes e metadados removidos: ").append(metadataEntries).append(" (").append(VaultRepository.formatBytes(metadataBytes)).append(")\n")
            append("Detalhes liberados da memória: ").append(metadataMemoryEntries).append(" item(ns)\n")
            append("Durações liberadas da memória: ").append(durationMemoryEntries).append(" vídeo(s)\n")
            if (runtimeCacheProtected) {
                append("Cache temporário: preservado porque há uma gravação em andamento\n")
            } else {
                append("Cache temporário removido: ").append(cacheFiles).append(" (").append(VaultRepository.formatBytes(cacheBytes)).append(")\n")
            }
            append("Metadados de álbum órfãos: ").append(mainAlbumMetadata).append("\n")
            append("Metadados de lixeira órfãos: ").append(trashMetadata).append("\n")
            append("Pendências antigas corrigidas: ").append(startupCleanedFiles)
        }
    }

    fun cacheSnapshot(context: Context): CacheSnapshot {
        val app = context.applicationContext
        val thumbnails = MediaThumbnailRepository.cacheStats(app)
        val metadata = VaultRepository.metadataCacheStats(app)
        val runtime = runtimeCacheStats(app)
        return CacheSnapshot(
            thumbnailFiles = thumbnails.diskFiles,
            thumbnailDiskBytes = thumbnails.diskBytes,
            thumbnailMemoryBytes = thumbnails.memoryBytes,
            thumbnailMemoryCaches = thumbnails.memoryCaches,
            metadataEntries = metadata.persistentEntries,
            metadataDiskBytes = metadata.persistentBytes,
            metadataMemoryEntries = metadata.memoryEntries,
            durationMemoryEntries = metadata.durationMemoryEntries,
            runtimeFiles = runtime.first,
            runtimeBytes = runtime.second
        )
    }

    fun cleanCacheAndDirtyData(context: Context): CleanupReport {
        val app = context.applicationContext
        val recordingRecovery = RecordingRecoveryRepository.recoverStaleRecordings(app)
        val startup = VaultRepository.runStartupMaintenance(app)
        val staleMediaWorkFiles = cleanStaleMediaWorkFiles(app, System.currentTimeMillis() - MANUAL_STALE_WORK_MS)
        val thumbs = MediaThumbnailRepository.clearAll(app)
        val metadata = VaultRepository.clearMetadataCache(app)
        val primaryFiles = VaultRepository.primaryDirectory(app)
            .listFiles { file -> file.isFile && VaultMediaFormats.isSupported(file.name) }
            .orEmpty()
            .toList()
        val albumMetadata = VaultAlbumStore.cleanMissing(app, primaryFiles)
        val trashMetadata = VaultTrashRepository.cleanOrphanMetadata(app)
        val runtimeCacheProtected = CaptureStateStore.isBusy(app)
        val cache = if (runtimeCacheProtected) 0 to 0L else cleanRuntimeCache(app)
        return CleanupReport(
            thumbnailFiles = thumbs.files,
            thumbnailBytes = thumbs.bytes,
            thumbnailMemoryBytes = thumbs.memoryBytes,
            metadataEntries = metadata.persistentEntries,
            metadataBytes = metadata.persistentBytes,
            metadataMemoryEntries = metadata.memoryEntries,
            durationMemoryEntries = metadata.durationMemoryEntries,
            cacheFiles = cache.first,
            cacheBytes = cache.second,
            runtimeCacheProtected = runtimeCacheProtected,
            mainAlbumMetadata = albumMetadata,
            trashMetadata = trashMetadata,
            startupCleanedFiles = startup.cleanedFiles +
                staleMediaWorkFiles +
                recordingRecovery.recoveredFiles +
                recordingRecovery.preservedBrokenFiles
        )
    }



    fun runStartupCleanup(context: Context) {
        val app = context.applicationContext
        val cutoff = System.currentTimeMillis() - STARTUP_STALE_WORK_MS
        cleanStaleMediaWorkFiles(app, cutoff)
    }

    private fun cleanRuntimeCache(context: Context): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        runtimeRoots(context)
            .forEach { dir ->
                deleteChildren(dir) { count, size ->
                    files += count
                    bytes += size
                }
            }
        return files to bytes
    }

    private fun runtimeCacheStats(context: Context): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        runtimeRoots(context)
            .forEach { dir ->
                val metrics = dir.safeMetrics()
                files += metrics.files
                bytes += metrics.bytes
            }
        return files to bytes
    }

    private fun deleteChildren(root: File, onDeleted: (Int, Long) -> Unit) {
        if (!root.exists()) return
        root.listFiles().orEmpty().forEach { child ->
            if (containsActiveTemporary(child)) return@forEach
            if (RecordingRecoveryRepository.shouldProtectFromCacheCleanup(child)) return@forEach
            val metrics = child.safeMetrics()
            if (child.deleteRecursively()) onDeleted(metrics.files, metrics.bytes)
        }
    }

    private fun runtimeRoots(context: Context): List<File> =
        listOfNotNull(context.cacheDir, context.externalCacheDir)
            .distinctBy(::normalizedPath)


    private fun cleanStaleMediaWorkFiles(context: Context, cutoff: Long): Int {
        var cleaned = 0
        listOf(
            VaultRepository.primaryDirectory(context),
            SecondaryVaultRepository.directory(context),
            TertiaryVaultRepository.directory(context),
            VaultTrashRepository.directory(context)
        ).distinctBy(::normalizedPath).forEach { directory ->
            directory.listFiles().orEmpty().forEach { file ->
                if (file.isFile &&
                    file.lastModified() in 1L until cutoff &&
                    TEMPORARY_MEDIA_SUFFIXES.any(file.name::endsWith) &&
                    !containsActiveTemporary(file) &&
                    file.delete()
                ) {
                    cleaned++
                }
            }
        }
        return cleaned
    }

    private fun containsActiveTemporary(file: File): Boolean {
        val root = normalizedPath(file)
        return synchronized(activeTemporaryPaths) {
            activeTemporaryPaths.any { active ->
                active == root ||
                    active.startsWith(root + File.separator) ||
                    root.startsWith(active + File.separator)
            }
        }
    }

    private fun normalizedPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absoluteFile.normalize().path }

    private fun File.safeMetrics(): CacheMetrics = runCatching {
        if (isFile) return@runCatching CacheMetrics(1, length().coerceAtLeast(0L))
        var files = 0
        var bytes = 0L
        walkBottomUp().forEach { child ->
            if (child.isFile) {
                files++
                bytes += child.length().coerceAtLeast(0L)
            }
        }
        CacheMetrics(files, bytes)
    }.getOrDefault(CacheMetrics(0, 0L))

    private data class CacheMetrics(val files: Int, val bytes: Long)

    private const val MANUAL_STALE_WORK_MS = 60L * 60L * 1_000L
    private const val STARTUP_STALE_WORK_MS = 6L * 60L * 60L * 1_000L
    private val TEMPORARY_MEDIA_SUFFIXES = setOf(".download", ".part")
}
