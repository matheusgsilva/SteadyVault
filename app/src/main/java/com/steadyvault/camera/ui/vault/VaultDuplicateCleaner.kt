package com.steadyvault.camera.ui.vault

import android.content.Context
import com.steadyvault.camera.storage.vault.PrivateMediaFileWriter
import com.steadyvault.camera.storage.vault.SecondaryVaultRepository
import com.steadyvault.camera.storage.vault.TertiaryVaultRepository
import com.steadyvault.camera.storage.vault.VaultAlbumStore
import com.steadyvault.camera.storage.vault.VaultAreaId
import com.steadyvault.camera.storage.vault.VaultRepository
import com.steadyvault.camera.storage.vault.VaultTrashRepository

/**
 * Remove somente arquivos byte a byte idênticos. Funciona nos três cofres e,
 * no cofre principal, também pode limitar a limpeza a um álbum específico.
 */
internal object VaultDuplicateCleaner {
    enum class Stage { ANALYZING, REMOVING }

    data class Progress(val stage: Stage, val current: Int, val total: Int) {
        fun label(): String = when (stage) {
            Stage.ANALYZING -> if (total <= 0) "Analisando mídias…" else "Verificando duplicados $current/$total…"
            Stage.REMOVING -> if (total <= 0) "Removendo duplicados…" else "Movendo duplicados para a lixeira $current/$total…"
        }
    }

    data class Result(
        val checkedItems: Int,
        val duplicatesFound: Int,
        val movedToTrash: Int,
        val failedToMove: Int
    )

    fun cleanVault(context: Context, area: String, onProgress: (Progress) -> Unit = {}): Result {
        require(VaultAreaId.isValid(area)) { "Cofre inválido" }
        return clean(context, area, listArea(context, area), onProgress)
    }

    fun cleanPrimaryAlbum(context: Context, albumId: String, onProgress: (Progress) -> Unit = {}): Result {
        val all = VaultRepository.list(context)
        val assignments = VaultAlbumStore.assignmentSnapshot(context, all.map { it.file })
        val albumItems = all.filter { item -> item.file.isFile && assignments[item.file.absolutePath] == albumId }
        return clean(context, VaultAreaId.PRIMARY, albumItems, onProgress)
    }

    private fun clean(context: Context, area: String, sourceItems: List<VaultRepository.MediaItem>, onProgress: (Progress) -> Unit): Result {
        val items = sourceItems.filter { it.file.isFile }
        val candidateGroups = items
            .groupBy { it.file.length().coerceAtLeast(0L) }
            .filterKeys { it > 0L }
            .values
            .filter { it.size > 1 }

        val candidateCount = candidateGroups.sumOf { it.size }
        if (candidateCount == 0) return Result(items.size, 0, 0, 0)

        val duplicates = ArrayList<VaultRepository.MediaItem>()
        var analyzed = 0
        candidateGroups.forEach { group ->
            val keepByHash = LinkedHashMap<String, VaultRepository.MediaItem>()
            group.sortedWith(
                compareBy<VaultRepository.MediaItem> { it.modifiedAt }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.file.absolutePath }
            ).forEach { item ->
                val hash = VaultImportDedupStore.contentHashForFile(context, area, item.file)
                    ?: runCatching { PrivateMediaFileWriter.sha256(item.file) }.getOrNull()?.also { calculated ->
                        VaultImportDedupStore.rememberContentHash(context, area, item.file, calculated)
                    }
                analyzed++
                if (analyzed == candidateCount || analyzed % PROGRESS_STEP == 0) onProgress(Progress(Stage.ANALYZING, analyzed, candidateCount))
                if (hash.isNullOrBlank()) return@forEach
                if (keepByHash.putIfAbsent(hash, item) != null) duplicates += item
            }
        }

        if (duplicates.isEmpty()) return Result(items.size, 0, 0, 0)

        var moved = 0
        var failed = 0
        duplicates.forEachIndexed { index, item ->
            if (item.file.isFile && VaultTrashRepository.moveToTrash(context, item, warmThumbnail = false) != null) moved++ else failed++
            val done = index + 1
            if (done == duplicates.size || done % PROGRESS_STEP == 0) onProgress(Progress(Stage.REMOVING, done, duplicates.size))
        }

        if (area == VaultAreaId.PRIMARY) VaultAlbumStore.cleanMissing(context, VaultRepository.list(context).map { it.file })
        VaultImportDedupStore.cleanupArea(context, area, directoryFor(context, area))
        return Result(items.size, duplicates.size, moved, failed)
    }

    private fun listArea(context: Context, area: String): List<VaultRepository.MediaItem> = when (area) {
        VaultAreaId.PRIMARY -> VaultRepository.list(context)
        VaultAreaId.SECONDARY -> SecondaryVaultRepository.list(context)
        VaultAreaId.TERTIARY -> TertiaryVaultRepository.list(context)
        else -> emptyList()
    }

    private fun directoryFor(context: Context, area: String) = when (area) {
        VaultAreaId.PRIMARY -> VaultRepository.primaryDirectory(context)
        VaultAreaId.SECONDARY -> SecondaryVaultRepository.directory(context)
        VaultAreaId.TERTIARY -> TertiaryVaultRepository.directory(context)
        else -> VaultRepository.primaryDirectory(context)
    }

    private const val PROGRESS_STEP = 4
}
