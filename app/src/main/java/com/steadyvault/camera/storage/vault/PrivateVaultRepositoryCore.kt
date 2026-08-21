package com.steadyvault.camera.storage.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

internal object PrivateVaultRepositoryCore {
    fun directory(context: Context, area: String): File = File(context.filesDir, "vaults/$area").apply { mkdirs() }

    fun list(context: Context, area: String): List<VaultRepository.MediaItem> {
        val dir = directory(context, area)
        PrivateVaultMediaIndex.reconcile(context, area, dir)
        return PrivateVaultMediaIndex.listAll(context, area)
    }



    fun usedBytes(context: Context, area: String): Long {
        val dir = directory(context, area)
        PrivateVaultMediaIndex.reconcile(context, area, dir)
        return PrivateVaultMediaIndex.summary(context, area).bytes
    }

    fun importFromUri(context: Context, area: String, uri: Uri): VaultRepository.MediaItem {
        val resolver = context.contentResolver
        var displayName: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) displayName = cursor.getString(0) }
        }
        val mime = resolver.getType(uri).orEmpty()
        val requested = VaultMediaFormats.importFileName(displayName, mime, "IMG")
        require(VaultMediaFormats.isSupported(requested, mime)) { "Use uma foto ou vídeo compatível" }
        val target = uniqueFile(directory(context, area), requested)
        try {
            PrivateMediaFileWriter.copyFromUri(context, uri, target)
            val item = VaultRepository.readItem(target, fast = true) ?: throw IllegalStateException("Use uma foto ou vídeo compatível")
            PrivateVaultMediaIndex.update(context, area, item)
            return item
        } catch (throwable: Throwable) {
            target.delete()
            PrivateVaultMediaIndex.invalidate(area)
            throw throwable
        }
    }

    fun findByPath(context: Context, area: String, path: String): VaultRepository.MediaItem? {
        val file = File(path)
        if (!isInside(context, area, file)) return null
        return VaultRepository.readItem(file)
    }

    fun exportToGallery(context: Context, item: VaultRepository.MediaItem): Uri = PublicMediaExporter.export(context, item, "DCIM/Camera")

    fun deletePermanently(context: Context, area: String, item: VaultRepository.MediaItem): Boolean {
        if (!isInside(context, area, item.file) || VaultRepository.isBeingProcessed(item.file) || VaultRepository.isBeingViewed(item.file)) return false
        VaultRepository.removeCachedMediaData(context, item.file)
        val deleted = item.file.delete()
        if (deleted) PrivateVaultMediaIndex.remove(context, area, item.file)
        return deleted
    }

    private fun isInside(context: Context, area: String, file: File): Boolean = runCatching {
        val root = directory(context, area).canonicalFile
        val candidate = file.canonicalFile
        candidate.isFile && candidate.path.startsWith(root.path + File.separator)
    }.getOrDefault(false)

    private fun uniqueFile(directory: File, requestedName: String): File {
        val safe = VaultMediaFormats.sanitizeName(requestedName)
        val dot = safe.lastIndexOf('.')
        val fileStem = if (dot > 0) safe.substring(0, dot) else safe
        val extension = if (dot > 0) safe.substring(dot) else ""
        var candidate = File(directory, safe)
        var index = 1
        while (candidate.exists()) candidate = File(directory, "${fileStem}_${index++}$extension")
        return candidate
    }
}
