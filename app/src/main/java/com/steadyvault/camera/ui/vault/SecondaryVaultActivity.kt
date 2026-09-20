package com.steadyvault.camera.ui.vault

import androidx.media3.common.util.UnstableApi
import com.steadyvault.camera.storage.security.SecondaryVaultLock
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultAreaId
import com.steadyvault.camera.storage.vault.VaultRepository

@UnstableApi
class SecondaryVaultActivity : PrivateVaultGalleryActivity() {
    override val vaultArea = VaultAreaId.SECONDARY
    override val vaultTitle = "Cofre secundário"
    override val vaultPrefsName = "secondary_vault_view_prefs"
    override val mediaPlayerVaultExtra = MediaPlayerActivity.EXTRA_SECONDARY
    override fun isVaultUnlocked() = SecondaryVaultLock.isUnlocked(this)
    override fun unlockVaultSession() = SecondaryVaultLock.unlockSession()
    override fun lockVault() = SecondaryVaultLock.lock()
    override fun listVaultAll() = SecondaryVaultRepository.list(this)
    override fun vaultUsedBytes() = SecondaryVaultRepository.usedBytes(this)
    override fun deletePermanently(item: VaultRepository.MediaItem) = SecondaryVaultRepository.deletePermanently(this, item)
    override fun exportToGallery(item: VaultRepository.MediaItem) = SecondaryVaultRepository.exportToGallery(this, item)
}
