package com.steadyvault.camera.storage.vault

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

object PublicMediaExporter {
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    fun export(context: Context, item: VaultRepository.MediaItem, relativePath: String): Uri {
        val resolver = context.contentResolver
        val collection = if (item.video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000L)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("Não foi possível criar a cópia na galeria")
        try {
            resolver.openOutputStream(uri, "w")?.buffered()?.use { output ->
                item.file.inputStream().buffered().use { input -> input.copyTo(output, COPY_BUFFER_SIZE) }
            } ?: throw IllegalStateException("Não foi possível abrir a cópia na galeria")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            PublicMediaRegistry.add(context, uri, item.name)
            return uri
        } catch (throwable: Throwable) {
            resolver.delete(uri, null, null)
            throw throwable
        }
    }
}
