package com.steadyvault.camera.ui.vault

import com.steadyvault.camera.storage.vault.VaultRepository
import java.util.Locale

internal enum class MediaTypeFilter(val label: String) {
    ALL("Todas"), PHOTOS("Fotos"), VIDEOS("Vídeos");

    fun matches(item: VaultRepository.MediaItem): Boolean = when (this) {
        ALL -> true
        PHOTOS -> !item.video
        VIDEOS -> item.video
    }

    companion object {
        fun from(value: String?): MediaTypeFilter = values().firstOrNull { it.name == value } ?: ALL
    }
}

internal enum class PeriodFilter(val label: String) {
    ALL("Todo o período"), TODAY("Hoje"), LAST_7_DAYS("Últimos 7 dias"), LAST_30_DAYS("Últimos 30 dias");

    fun cutoffMillis(now: Long): Long? = when (this) {
        ALL -> null
        TODAY -> java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        LAST_7_DAYS -> now - 7L * 24L * 60L * 60L * 1000L
        LAST_30_DAYS -> now - 30L * 24L * 60L * 60L * 1000L
    }

    companion object {
        fun from(value: String?): PeriodFilter = values().firstOrNull { it.name == value } ?: ALL
    }
}

internal enum class SortMode(val label: String) {
    NEWEST("Mais recentes"),
    OLDEST("Mais antigas"),
    NAME_AZ("Nome A–Z"),
    NAME_ZA("Nome Z–A"),
    SIZE_LARGEST("Maior tamanho"),
    SIZE_SMALLEST("Menor tamanho"),
    DURATION_LONGEST("Maior duração"),
    DURATION_SHORTEST("Menor duração"),
    RESOLUTION_HIGHEST("Maior resolução"),
    RESOLUTION_LOWEST("Menor resolução");

    fun requiresDetailedMetadata(): Boolean = this == DURATION_LONGEST || this == DURATION_SHORTEST || this == RESOLUTION_HIGHEST || this == RESOLUTION_LOWEST

    fun needsDetailedMetadata(item: VaultRepository.MediaItem): Boolean = when (this) {
        DURATION_LONGEST, DURATION_SHORTEST -> item.video && item.durationMs <= 0L
        RESOLUTION_HIGHEST, RESOLUTION_LOWEST -> item.width <= 0 || item.height <= 0
        else -> false
    }

    fun sort(items: MutableList<VaultRepository.MediaItem>) {
        if (items.size < 2) return
        val sorted = when (this) {
            NEWEST -> items.sortedWith(compareByDescending<VaultRepository.MediaItem> { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            OLDEST -> items.sortedWith(compareBy<VaultRepository.MediaItem> { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            NAME_AZ -> items.map { it to it.name.lowercase(Locale.ROOT) }
                .sortedWith(compareBy<Pair<VaultRepository.MediaItem, String>> { it.second }.thenByDescending { it.first.modifiedAt })
                .map { it.first }
            NAME_ZA -> items.map { it to it.name.lowercase(Locale.ROOT) }
                .sortedWith(compareByDescending<Pair<VaultRepository.MediaItem, String>> { it.second }.thenByDescending { it.first.modifiedAt })
                .map { it.first }
            SIZE_LARGEST -> items.sortedWith(compareByDescending<VaultRepository.MediaItem> { it.sizeBytes }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            SIZE_SMALLEST -> items.sortedWith(compareBy<VaultRepository.MediaItem> { it.sizeBytes }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            DURATION_LONGEST -> items.sortedWith(compareByDescending<VaultRepository.MediaItem> { it.durationMs }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            DURATION_SHORTEST -> items.sortedWith(compareBy<VaultRepository.MediaItem> { if (it.video && it.durationMs > 0L) it.durationMs else Long.MAX_VALUE }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            RESOLUTION_HIGHEST -> items.sortedWith(compareByDescending<VaultRepository.MediaItem> { it.width.toLong() * it.height.toLong() }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            RESOLUTION_LOWEST -> items.sortedWith(compareBy<VaultRepository.MediaItem> { if (it.width > 0 && it.height > 0) it.width.toLong() * it.height.toLong() else Long.MAX_VALUE }.thenByDescending { it.modifiedAt }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        }
        items.clear()
        items.addAll(sorted)
    }

    companion object {
        fun from(value: String?): SortMode = values().firstOrNull { it.name == value } ?: NEWEST
    }
}
