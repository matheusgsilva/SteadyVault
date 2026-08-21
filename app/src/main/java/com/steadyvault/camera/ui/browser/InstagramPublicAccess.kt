package com.steadyvault.camera.ui.browser

import java.net.URI
import java.util.Locale

/** Regras de acesso público do Instagram, sem depender de uma sessão do WebView. */
internal object InstagramPublicAccess {
    fun isInstagramUrl(value: String): Boolean = runCatching {
        val host = URI(value.trim()).host.orEmpty().lowercase(Locale.US)
        host == "instagram.com" || host.endsWith(".instagram.com")
    }.getOrDefault(false)

    fun normalize(value: String): String {
        val trimmed = value.trim()
        if (!isInstagramUrl(trimmed)) return trimmed
        return runCatching {
            val uri = URI(trimmed)
            val path = uri.path.orEmpty().ifBlank { "/" }
            URI("https", "www.instagram.com", path, null).toASCIIString()
        }.getOrDefault(trimmed)
    }

    fun fallbackUrls(value: String): List<String> = runCatching {
        if (!isInstagramUrl(value)) return emptyList()
        val parts = URI(value).path.orEmpty().split('/').filter(String::isNotBlank)
        val typeIndex = parts.indexOfFirst { it.lowercase(Locale.US) in POST_TYPES }
        val shortcode = parts.getOrNull(typeIndex + 1)?.takeIf(String::isNotBlank) ?: return emptyList()
        val requestedType = parts.getOrNull(typeIndex).orEmpty().lowercase(Locale.US)
        val primaryType = if (requestedType == "reel" || requestedType == "reels") "reel" else "p"
        listOf(
            "https://www.instagram.com/$primaryType/$shortcode/",
            "https://www.instagram.com/$primaryType/$shortcode/embed/",
            "https://www.instagram.com/p/$shortcode/embed/"
        ).distinct().filterNot { it == normalize(value) }
    }.getOrDefault(emptyList())

    fun publicPageUrls(value: String): List<String> {
        val normalized = normalize(value)
        val fallbacks = fallbackUrls(normalized)
        return (fallbacks.filter(::isEmbedUrl) + normalized + fallbacks.filterNot(::isEmbedUrl)).distinct()
    }

    fun isEmbedUrl(value: String): Boolean = runCatching {
        isInstagramUrl(value) && URI(value).path.orEmpty().trimEnd('/').endsWith("/embed", ignoreCase = true)
    }.getOrDefault(false)

    fun hasAuthenticatedSession(cookieHeader: String): Boolean = cookieHeader
        .split(';')
        .map { it.trim() }
        .any { pair ->
            pair.substringBefore('=', "").equals("sessionid", ignoreCase = true) &&
                pair.substringAfter('=', "").isNotBlank()
        }

    private val POST_TYPES = setOf("p", "reel", "reels", "tv")
}
