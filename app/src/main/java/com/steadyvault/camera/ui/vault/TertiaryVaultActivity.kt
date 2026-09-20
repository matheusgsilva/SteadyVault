package com.steadyvault.camera.ui.vault

import androidx.media3.common.util.UnstableApi
import com.steadyvault.camera.storage.security.TertiaryVaultLock
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultAreaId
import com.steadyvault.camera.storage.vault.VaultRepository

@UnstableApi
class TertiaryVaultActivity : PrivateVaultGalleryActivity() {
    override val vaultArea = VaultAreaId.TERTIARY
    override val vaultTitle = "Cofre terciário"
    override val vaultPrefsName = "tertiary_vault_view_prefs"
    override val mediaPlayerVaultExtra = MediaPlayerActivity.EXTRA_TERTIARY
    override fun isVaultUnlocked() = TertiaryVaultLock.isUnlocked(this)
    override fun unlockVaultSession() = TertiaryVaultLock.unlockSession()
    override fun lockVault() = TertiaryVaultLock.lock()
    override fun listVaultAll() = TertiaryVaultRepository.list(this)
    override fun vaultUsedBytes() = TertiaryVaultRepository.usedBytes(this)
    override fun deletePermanently(item: VaultRepository.MediaItem) = TertiaryVaultRepository.deletePermanently(this, item)
    override fun exportToGallery(item: VaultRepository.MediaItem) = TertiaryVaultRepository.exportToGallery(this, item)
}
