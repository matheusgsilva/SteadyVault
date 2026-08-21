package com.steadyvault.camera.ui.browser

import com.steadyvault.camera.storage.vault.VaultAreaId
import android.content.Context

object PrivateBrowserStore {
    private const val PREF = "steadyvault_private_browser"
    private const val KEY_FAVORITES = "favorites"
    private const val FAVORITE_SEPARATOR = "\u001F"
    private const val KEY_AD_BLOCK = "ad_block"
    private const val KEY_DESKTOP = "desktop_mode"
    private const val KEY_THIRD_PARTY_COOKIES = "third_party_cookies"
    private const val KEY_MIXED_CONTENT = "mixed_content_compatibility"
    private const val KEY_DOWNLOAD_DESTINATION = "download_destination"
    private const val KEY_MEDIA_QUALITY = "media_quality"
    private const val KEY_MEDIA_RESOLUTION = "media_resolution"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_ARIA2 = "aria2"
    private const val KEY_EMBED_METADATA = "embed_metadata"
    private const val KEY_VERIFY_FREE_SPACE = "verify_free_space"
    private const val KEY_PERFORMANCE_PROFILE = "performance_profile"

    const val DESTINATION_ASK = "ask"
    const val DESTINATION_PRIMARY = VaultAreaId.PRIMARY
    const val DESTINATION_SECONDARY = VaultAreaId.SECONDARY
    const val DESTINATION_TERTIARY = VaultAreaId.TERTIARY
    const val DESTINATION_DOWNLOADS = "downloads"

    const val QUALITY_AUTO = "auto"
    const val QUALITY_MAX = "max"
    const val QUALITY_BALANCED = "balanced"
    const val QUALITY_SMALL = "small"

    const val RESOLUTION_ORIGINAL = "original"
    const val RESOLUTION_2160 = "2160p"
    const val RESOLUTION_1440 = "1440p"
    const val RESOLUTION_1080 = "1080p"
    const val RESOLUTION_720 = "720p"
    const val RESOLUTION_480 = "480p"

    const val PERFORMANCE_FAST = "fast"
    const val PERFORMANCE_BALANCED = "balanced"
    const val PERFORMANCE_COMPATIBLE = "compatible"

    data class Favorite(val title: String, val url: String)

    fun favorites(context: Context): List<Favorite> = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getStringSet(KEY_FAVORITES, emptySet())
        .orEmpty()
        .mapNotNull { raw ->
            val parts = raw.split(FAVORITE_SEPARATOR, limit = 2)
            val title = parts.getOrNull(0).orEmpty().trim()
            val url = parts.getOrNull(1).orEmpty().trim()
            if (url.isBlank()) null else Favorite(title.ifBlank { favoriteTitle(url) }, url)
        }
        .distinctBy { it.url }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })

    fun isFavorite(context: Context, url: String?): Boolean {
        val clean = normalizeFavoriteUrl(url) ?: return false
        return favorites(context).any { normalizeFavoriteUrl(it.url) == clean }
    }

    fun addFavorite(context: Context, title: String?, url: String?): Boolean {
        val clean = normalizeFavoriteUrl(url) ?: return false
        val label = title.orEmpty().trim().ifBlank { favoriteTitle(clean) }
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
            .filterNot { it.substringAfter(FAVORITE_SEPARATOR, "").trim() == clean }
            .toMutableSet()
        updated += "$label$FAVORITE_SEPARATOR$clean"
        prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
        return true
    }

    fun removeFavorite(context: Context, url: String?): Boolean {
        val clean = normalizeFavoriteUrl(url) ?: return false
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
        val updated = current.filterNot { it.substringAfter(FAVORITE_SEPARATOR, "").trim() == clean }.toSet()
        if (updated.size == current.size) return false
        prefs.edit().putStringSet(KEY_FAVORITES, updated).apply()
        return true
    }

    private fun normalizeFavoriteUrl(url: String?): String? = url
        ?.trim()
        ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun favoriteTitle(url: String): String = url
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifBlank { "Favorito" }

    fun adBlockEnabled(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_AD_BLOCK, true)

    fun setAdBlockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_AD_BLOCK, enabled).apply()
    }

    fun desktopMode(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_DESKTOP, false)

    fun setDesktopMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESKTOP, enabled).apply()
    }

    fun thirdPartyCookiesEnabled(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_THIRD_PARTY_COOKIES, true)

    fun setThirdPartyCookiesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_THIRD_PARTY_COOKIES, enabled).apply()
    }

    fun mixedContentCompatibility(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getBoolean(KEY_MIXED_CONTENT, false)

    fun setMixedContentCompatibility(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_MIXED_CONTENT, enabled).apply()
    }

    fun downloadDestination(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY_DOWNLOAD_DESTINATION, DESTINATION_ASK)
        .orEmpty()
        .takeIf { it in destinationValues }
        ?: DESTINATION_ASK

    fun setDownloadDestination(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_DOWNLOAD_DESTINATION, value.takeIf { it in destinationValues } ?: DESTINATION_ASK)
            .apply()
    }

    fun mediaQuality(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY_MEDIA_QUALITY, QUALITY_AUTO)
        .orEmpty()
        .takeIf { it in qualityValues }
        ?: QUALITY_AUTO

    fun setMediaQuality(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_MEDIA_QUALITY, value.takeIf { it in qualityValues } ?: QUALITY_AUTO)
            .apply()
    }

    fun mediaResolution(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY_MEDIA_RESOLUTION, RESOLUTION_ORIGINAL)
        .orEmpty()
        .takeIf { it in resolutionValues }
        ?: RESOLUTION_ORIGINAL

    fun setMediaResolution(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY_MEDIA_RESOLUTION, value.takeIf { it in resolutionValues } ?: RESOLUTION_ORIGINAL)
            .apply()
    }


    fun wifiOnly(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_ONLY, false)
    fun setWifiOnly(context: Context, enabled: Boolean) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()

    fun aria2Enabled(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ARIA2, true)
    fun setAria2Enabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ARIA2, enabled).apply()

    fun embedMetadata(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_EMBED_METADATA, true)
    fun setEmbedMetadata(context: Context, enabled: Boolean) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_EMBED_METADATA, enabled).apply()

    fun verifyFreeSpace(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_VERIFY_FREE_SPACE, true)
    fun setVerifyFreeSpace(context: Context, enabled: Boolean) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_VERIFY_FREE_SPACE, enabled).apply()

    fun performanceProfile(context: Context): String = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(KEY_PERFORMANCE_PROFILE, PERFORMANCE_BALANCED).orEmpty().takeIf { it in performanceProfiles } ?: PERFORMANCE_BALANCED

    fun setPerformanceProfile(context: Context, value: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_PERFORMANCE_PROFILE, value.takeIf { it in performanceProfiles } ?: PERFORMANCE_BALANCED).apply()
    }

    fun performanceLabel(value: String): String = when (value) {
        PERFORMANCE_FAST -> "Rápido"
        PERFORMANCE_COMPATIBLE -> "Compatível"
        else -> "Equilibrado"
    }

    fun advancedSummary(context: Context): String = buildList {
        add(performanceLabel(performanceProfile(context)))
        if (wifiOnly(context)) add("somente Wi‑Fi")
        if (aria2Enabled(context) && performanceProfile(context) != PERFORMANCE_COMPATIBLE) add("aria2")
        if (embedMetadata(context)) add("metadados")
    }.joinToString(" • ")

    fun destinationLabel(value: String): String = when (value) {
        DESTINATION_PRIMARY -> "Cofre principal"
        DESTINATION_SECONDARY -> "Cofre secundário"
        DESTINATION_TERTIARY -> "Cofre terciário"
        DESTINATION_DOWNLOADS -> "Downloads do celular"
        else -> "Perguntar sempre"
    }

    fun qualityLabel(value: String): String = when (value) {
        QUALITY_MAX -> "Máxima disponível"
        QUALITY_BALANCED -> "Equilibrada"
        QUALITY_SMALL -> "Arquivo menor"
        else -> "Automática"
    }

    fun resolutionLabel(value: String): String = when (value) {
        RESOLUTION_2160 -> "4K / 2160p"
        RESOLUTION_1440 -> "2K / 1440p"
        RESOLUTION_1080 -> "Full HD / 1080p"
        RESOLUTION_720 -> "HD / 720p"
        RESOLUTION_480 -> "480p"
        else -> "Original do site"
    }

    fun destinationSubtitle(value: String): String = when (value) {
        DESTINATION_PRIMARY -> "Salva mídia direto no cofre principal protegido."
        DESTINATION_SECONDARY -> "Salva mídia direto no cofre secundário."
        DESTINATION_TERTIARY -> "Salva mídia direto no cofre terciário."
        DESTINATION_DOWNLOADS -> "Usa o gerenciador de downloads do Android."
        else -> "Mostra uma escolha a cada download."
    }

    fun mediaPreferenceSummary(context: Context): String =
        "${qualityLabel(mediaQuality(context))} • ${resolutionLabel(mediaResolution(context))}"

    val destinationValues = listOf(DESTINATION_ASK, DESTINATION_PRIMARY, DESTINATION_SECONDARY, DESTINATION_TERTIARY, DESTINATION_DOWNLOADS)
    val qualityValues = listOf(QUALITY_AUTO, QUALITY_MAX, QUALITY_BALANCED, QUALITY_SMALL)
    val resolutionValues = listOf(RESOLUTION_ORIGINAL, RESOLUTION_2160, RESOLUTION_1440, RESOLUTION_1080, RESOLUTION_720, RESOLUTION_480)
    val performanceProfiles = listOf(PERFORMANCE_FAST, PERFORMANCE_BALANCED, PERFORMANCE_COMPATIBLE)
}
