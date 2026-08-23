package com.steadyvault.camera.storage.vault

import android.content.Context
import android.net.Uri
import java.io.File

object TertiaryVaultRepository {
    private const val AREA = "tertiary"
    fun directory(context: Context): File = PrivateVaultRepositoryCore.directory(context, AREA)
    fun list(context: Context): List<VaultRepository.MediaItem> = PrivateVaultRepositoryCore.list(context, AREA)
    fun importFromUri(context: Context, uri: Uri, onProgress: ((Long, Long) -> Unit)? = null): VaultRepository.MediaItem = PrivateVaultRepositoryCore.importFromUri(context, AREA, uri, onProgress)
    fun importFromUri(context: Context, uri: Uri, displayName: String?, mime: String, onProgress: ((Long, Long) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): VaultRepository.MediaItem = PrivateVaultRepositoryCore.importFromUri(context, AREA, uri, displayName, mime, onProgress, shouldCancel)
    fun importFromUriVerified(context: Context, uri: Uri, displayName: String?, mime: String, onProgress: ((Long, Long) -> Unit)? = null, shouldCancel: () -> Boolean = { false }): VaultRepository.ImportedMedia = PrivateVaultRepositoryCore.importFromUriVerified(context, AREA, uri, displayName, mime, onProgress, shouldCancel)
    fun findByPath(context: Context, path: String): VaultRepository.MediaItem? = PrivateVaultRepositoryCore.findByPath(context, AREA, path)
    fun usedBytes(context: Context): Long = PrivateVaultRepositoryCore.usedBytes(context, AREA)
    fun exportToGallery(context: Context, item: VaultRepository.MediaItem): Uri = PrivateVaultRepositoryCore.exportToGallery(context, item)
    fun deletePermanently(context: Context, item: VaultRepository.MediaItem): Boolean = PrivateVaultRepositoryCore.deletePermanently(context, AREA, item)
}
