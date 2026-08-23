package com.steadyvault.camera.storage.vault

import android.content.Context
import java.io.File
import java.util.UUID

object VaultTrashRepository {
    enum class Origin(val id: String, val label: String) {
        PRIMARY(VaultAreaId.PRIMARY, "Cofre principal"),
        SECONDARY(VaultAreaId.SECONDARY, "Cofre secundário"),
        TERTIARY(VaultAreaId.TERTIARY, "Cofre terciário");

        companion object {
            fun fromId(value: String): Origin? = Origin.values().firstOrNull { it.id == value }
        }
    }

    data class TrashItem(
        val file: File,
        val originalName: String,
        val deletedAt: Long,
        val origin: Origin,
        val media: VaultRepository.MediaItem
    )

    const val RETENTION_7_DAYS = 7
    const val RETENTION_15_DAYS = 15
    const val RETENTION_30_DAYS = 30
    const val RETENTION_NEVER = 0

    private const val PREFS = "steadyvault_vault_trash"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val PREFIX_ALBUM = "trash_album_"
    private const val SEPARATOR = "___SVTRASH___"

    fun directory(context: Context): File = File(context.filesDir, "vaults/trash").apply { mkdirs() }

    fun retentionDays(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(KEY_RETENTION_DAYS, RETENTION_30_DAYS)

    fun setRetentionDays(context: Context, days: Int) {
        val safe = days.takeIf { it in setOf(RETENTION_7_DAYS, RETENTION_15_DAYS, RETENTION_30_DAYS, RETENTION_NEVER) }
            ?: RETENTION_30_DAYS
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_RETENTION_DAYS, safe).apply()
        purgeExpired(context)
    }

    @Synchronized
    fun moveToTrash(context: Context, item: VaultRepository.MediaItem, warmThumbnail: Boolean = true): TrashItem? {
        if (VaultRepository.isBeingProcessed(item.file) || VaultRepository.isBeingViewed(item.file)) return null
        val origin = originFor(context, item.file) ?: return null
        val deletedAt = System.currentTimeMillis()
        val albumId = if (origin == Origin.PRIMARY) VaultAlbumStore.albumIdFor(context, item.file) else null
        val safeName = item.name.replace(SEPARATOR, "_")
        val target = File(
            directory(context),
            "$deletedAt$SEPARATOR${UUID.randomUUID()}$SEPARATOR${origin.id}$SEPARATOR$safeName"
        )
        VaultRepository.removeCachedMediaData(context, item.file)
        val moved = moveFile(item.file, target)
        if (!moved || !target.isFile) {
            target.delete()
            return null
        }
        target.setLastModified(item.modifiedAt)
        if (albumId != null) prefs(context).edit().putString(PREFIX_ALBUM + target.name, albumId).apply()
        if (origin == Origin.PRIMARY) VaultAlbumStore.removeMetadata(context, item.file)
        if (warmThumbnail) MediaThumbnailRepository.warmUp(context, target, item.video)
        return TrashItem(target, item.name, deletedAt, origin, item.copy(file = target))
    }

    @Synchronized
    fun list(context: Context): List<TrashItem> {
        purgeExpired(context)
        return directory(context).listFiles().orEmpty()
            .mapNotNull(::parse)
            .sortedByDescending { it.deletedAt }
            .also { items -> items.forEach { MediaThumbnailRepository.warmUp(context, it.file, it.media.video) } }
    }

    @Synchronized
    fun restore(context: Context, item: TrashItem): VaultRepository.MediaItem? {
        val root = when (item.origin) {
            Origin.PRIMARY -> VaultRepository.primaryDirectory(context)
            Origin.SECONDARY -> SecondaryVaultRepository.directory(context)
            Origin.TERTIARY -> TertiaryVaultRepository.directory(context)
        }
        val target = uniqueFile(root, item.originalName)
        VaultRepository.removeCachedMediaData(context, item.file)
        val moved = moveFile(item.file, target)
        if (!moved || !target.isFile) {
            target.delete()
            return null
        }
        val restored = VaultRepository.readItem(target)
        if (restored == null) {
            moveFile(target, item.file)
            return null
        }
        if (item.origin == Origin.PRIMARY) {
            val albumId = prefs(context).getString(PREFIX_ALBUM + item.file.name, null)
            if (albumId != null && VaultAlbumStore.albums(context).any { it.id == albumId }) {
                VaultAlbumStore.assign(context, listOf(target), albumId)
            }
        }
        prefs(context).edit().remove(PREFIX_ALBUM + item.file.name).apply()
        MediaThumbnailRepository.warmUp(context, target, restored.video)
        return restored
    }

    @Synchronized
    fun deletePermanently(context: Context, item: TrashItem): Boolean {
        VaultRepository.removeCachedMediaData(context, item.file)
        val deleted = item.file.delete()
        if (deleted) prefs(context).edit().remove(PREFIX_ALBUM + item.file.name).apply()
        return deleted
    }

    @Synchronized
    fun empty(context: Context): Int {
        var deleted = 0
        directory(context).listFiles().orEmpty().forEach { file ->
            VaultRepository.removeCachedMediaData(context, file)
            if (file.delete()) {
                deleted++
                prefs(context).edit().remove(PREFIX_ALBUM + file.name).apply()
            }
        }
        return deleted
    }

    @Synchronized
    fun purgeExpired(context: Context): Int {
        val days = retentionDays(context)
        if (days == RETENTION_NEVER) return 0
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        var deleted = 0
        directory(context).listFiles().orEmpty().forEach { file ->
            val deletedAt = deletedAtFromName(file)
            if (deletedAt == null || deletedAt < cutoff) {
                VaultRepository.removeCachedMediaData(context, file)
                if (file.delete()) {
                    deleted++
                    prefs(context).edit().remove(PREFIX_ALBUM + file.name).apply()
                }
            }
        }
        return deleted
    }


    @Synchronized
    fun cleanOrphanMetadata(context: Context): Int {
        val validTrashNames = directory(context).listFiles().orEmpty()
            .filter { it.isFile }
            .mapTo(hashSetOf()) { PREFIX_ALBUM + it.name }
        val prefs = prefs(context)
        val editor = prefs.edit()
        var cleaned = 0
        prefs.all.keys.filter { it.startsWith(PREFIX_ALBUM) && it !in validTrashNames }.forEach { key ->
            editor.remove(key)
            cleaned++
        }
        if (cleaned > 0) editor.apply()
        return cleaned
    }

    fun count(context: Context): Int = directory(context).listFiles().orEmpty().count { it.isFile }

    fun usedBytes(context: Context): Long = directory(context).listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

    private fun originFor(context: Context, file: File): Origin? = runCatching {
        val candidate = file.canonicalFile
        when {
            isInside(candidate, VaultRepository.primaryDirectory(context).canonicalFile) -> Origin.PRIMARY
            isInside(candidate, SecondaryVaultRepository.directory(context).canonicalFile) -> Origin.SECONDARY
            isInside(candidate, TertiaryVaultRepository.directory(context).canonicalFile) -> Origin.TERTIARY
            else -> null
        }
    }.getOrNull()

    private fun isInside(file: File, root: File): Boolean =
        file.isFile && file.path.startsWith(root.path + File.separator)

    private fun deletedAtFromName(file: File): Long? =
        file.name.substringBefore(SEPARATOR, missingDelimiterValue = "").toLongOrNull()

    private fun parse(file: File): TrashItem? {
        if (!file.isFile) return null
        val parts = file.name.split(SEPARATOR, limit = 4)
        val deletedAt = parts.firstOrNull()?.toLongOrNull() ?: return null
        val (origin, originalName) = when (parts.size) {
            4 -> (Origin.fromId(parts[2]) ?: Origin.PRIMARY) to parts[3]
            else -> return null
        }
        val media = VaultRepository.readItem(file) ?: return null
        return TrashItem(file, originalName, deletedAt, origin, media.copy(name = originalName))
    }

    private fun moveFile(source: File, target: File): Boolean = source.renameTo(target) || runCatching {
        val expectedBytes = source.length()
        source.inputStream().buffered().use { input -> target.outputStream().buffered().use(input::copyTo) }
        if (target.length() != expectedBytes) error("Cópia incompleta")
        if (!source.delete()) error("Não foi possível remover o arquivo original")
        true
    }.getOrDefault(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uniqueFile(directory: File, requestedName: String): File {
        val clean = requestedName.replace(Regex("[\\/:*?\"<>|]"), "_").take(120).ifBlank { "Midia" }
        val dot = clean.lastIndexOf('.')
        val fileStem = if (dot > 0) clean.substring(0, dot) else clean
        val ext = if (dot > 0) clean.substring(dot) else ""
        var candidate = File(directory, clean)
        var index = 1
        while (candidate.exists()) candidate = File(directory, "${fileStem}_$index$ext").also { index++ }
        return candidate
    }
}
