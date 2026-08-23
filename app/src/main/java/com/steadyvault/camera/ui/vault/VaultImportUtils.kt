package com.steadyvault.camera.ui.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.steadyvault.camera.core.diagnostics.AppLogRepository
import com.steadyvault.camera.storage.vault.VaultMediaFormats
import java.util.ArrayDeque
import java.util.Locale

internal object VaultImportUtils {
    const val MAX_FOLDER_IMPORT_FILES = Int.MAX_VALUE

    data class SourceInfo(
        val uri: Uri,
        val displayName: String,
        val mime: String,
        val sizeBytes: Long
    )

    fun openFilesIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun openFolderIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun selectedDocumentUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            return buildList(clip.itemCount) {
                for (index in 0 until clip.itemCount) clip.getItemAt(index)?.uri?.let(::add)
            }
        }
        return data.data?.let(::listOf) ?: emptyList()
    }

    fun displayName(context: Context, uri: Uri): String = sourceInfo(context, uri).displayName

    fun sourceInfo(context: Context, uri: Uri): SourceInfo {
        val fallbackName = fallbackDisplayName(uri)
        val fallbackExtension = VaultMediaFormats.extensionOf(fallbackName)
        if (fallbackExtension in VaultMediaFormats.ALL_EXTENSIONS) {
            val video = fallbackExtension in VaultMediaFormats.VIDEO_EXTENSIONS
            return SourceInfo(uri, fallbackName, VaultMediaFormats.mimeFor(fallbackExtension, video), -1L)
        }

        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getStringOrNull(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                    size = cursor.getLongOrNull(cursor.getColumnIndex(OpenableColumns.SIZE)) ?: -1L
                }
            }
        }
        val resolvedName = name.orEmpty().ifBlank { fallbackName }
        val resolvedExtension = VaultMediaFormats.extensionOf(resolvedName)
        val mime = if (resolvedExtension in VaultMediaFormats.ALL_EXTENSIONS) {
            VaultMediaFormats.mimeFor(resolvedExtension, resolvedExtension in VaultMediaFormats.VIDEO_EXTENSIONS)
        } else {
            runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
        }
        return SourceInfo(uri, resolvedName, mime, size)
    }

    fun persistDocumentReadPermissions(context: Context, data: Intent?, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val flags = (data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val persistFlags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (persistFlags == 0) return
        var failures = 0
        for (uri in uris.distinctBy { it.toString() }) {
            if (runCatching { context.contentResolver.takePersistableUriPermission(uri, persistFlags) }.isFailure) failures++
        }
        if (failures > 0) AppLogRepository.warn(context, "import", "$failures URI(s) não aceitaram permissão persistente; a importação continuará com a permissão concedida pelo seletor enquanto disponível")
    }

    fun releaseDocumentReadPermissions(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val persisted: Set<String> = runCatching { context.contentResolver.persistedUriPermissions.mapTo(hashSetOf<String>()) { it.uri.toString() } }.getOrElse { emptySet() }
        if (persisted.isEmpty()) return
        uris.distinctBy { it.toString() }.filter { it.toString() in persisted }.forEach { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun persistTreeReadPermission(context: Context, data: Intent?, treeUri: Uri) {
        val readFlag = (data?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag == 0) return
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, readFlag) }
    }

    fun releaseTreeReadPermission(context: Context, treeUri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun mediaUrisFromTree(context: Context, treeUri: Uri, maxFiles: Int = MAX_FOLDER_IMPORT_FILES, onProgress: (Int) -> Unit = {}, shouldCancel: () -> Boolean = { false }): List<Uri> {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        val queue = ArrayDeque<String>()
        val result = linkedSetOf<Uri>()
        queue.add(rootId)

        while (!queue.isEmpty() && result.size < maxFiles && !shouldCancel()) {
            val parentId = queue.removeFirst()
            if (shouldSkipTreePath(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = runCatching {
                resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )
            }.onFailure { error ->
                AppLogRepository.warn(context, "import", "Pasta ignorada por falha de acesso: ${compactDocumentName(parentId)} • ${error.message.orEmpty()}")
            }.getOrNull() ?: continue

            cursor.use {
                val idColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (it.moveToNext() && result.size < maxFiles && !shouldCancel()) {
                    val documentId = it.getStringOrNull(idColumn) ?: continue
                    val name = it.getStringOrNull(nameColumn).orEmpty()
                    val mime = it.getStringOrNull(mimeColumn).orEmpty()
                    if (shouldSkipTreePath(documentId, name)) continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.add(documentId)
                    } else if (isSupportedMedia(name, mime)) {
                        runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId) }
                            .onSuccess(result::add)
                            .onFailure { error -> AppLogRepository.warn(context, "import", "Mídia ignorada por URI inválida: ${name.ifBlank { compactDocumentName(documentId) }} • ${error.message.orEmpty()}") }
                    }
                }
            }
            onProgress(result.size)
        }
        return result.toList()
    }

    fun isSupportedMedia(name: String, mime: String): Boolean = VaultMediaFormats.isSupported(name, mime)

    internal fun shouldSkipTreePath(documentId: String, displayName: String = ""): Boolean {
        val id = documentId.lowercase(Locale.ROOT).replace('\\', '/')
        val name = displayName.trim().lowercase(Locale.ROOT)
        if (name == "lost.dir" || name == "system volume information" || name == "\$recycle.bin") return true
        if (name == ".thumbnails" || name == ".trash" || name == ".trashed") return true
        if (name.contains("system cache") && name.contains("do not delete")) return true
        if (id.contains("system cache - do not delete")) return true
        if (id.contains("/system volume information/") || id.endsWith("/system volume information")) return true
        if (id.contains("/\$recycle.bin/") || id.endsWith("/\$recycle.bin")) return true
        if (id.contains("/lost.dir/") || id.endsWith("/lost.dir")) return true
        val parent = id.substringBeforeLast('/', "")
        if (parent.endsWith("/android") && (name == "data" || name == "obb")) return true
        return false
    }

    fun fallbackDisplayName(uri: Uri): String {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull().orEmpty()
        val raw = documentId.ifBlank { uri.lastPathSegment.orEmpty() }.ifBlank { uri.toString() }
        return compactDocumentName(raw)
    }

    private fun compactDocumentName(value: String): String = value.replace('\\', '/').substringAfterLast('/').substringAfterLast(':').ifBlank { value.takeLast(120) }

    private fun android.database.Cursor.getStringOrNull(column: Int): String? = if (column >= 0 && !isNull(column)) getString(column) else null
    private fun android.database.Cursor.getLongOrNull(column: Int): Long? = if (column >= 0 && !isNull(column)) getLong(column) else null
}
