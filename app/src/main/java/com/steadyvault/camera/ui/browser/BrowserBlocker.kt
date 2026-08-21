package com.steadyvault.camera.ui.browser

import android.net.Uri

object BrowserBlocker {
    /*
     * Bloqueia anúncios/rastreadores comuns sem filtrar categorias de conteúdo.
     * O app remove publicidade, pop-ups e telemetria óbvia.
     */
    private val blockedHostParts = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.",
        "pagead2.googlesyndication.com", "adsystem.com", "adnxs.com", "adsafeprotected.com",
        "taboola.com", "outbrain.com", "criteo.com", "rubiconproject.com", "pubmatic.com",
        "openx.net", "yieldmo.com", "scorecardresearch.com", "quantserve.com", "moatads.com",
        "media.net", "mgid.com", "revcontent.com", "popads.net", "propellerads.com",
        "adform.net", "smartadserver.com", "casalemedia.com", "bidswitch.net", "advertising.com",
        "analytics.google.com", "google-analytics.com", "googletagmanager.com", "facebook.net",
        "connect.facebook.net", "hotjar.com", "clarity.ms", "segment.io", "mixpanel.com",
        "amplitude.com", "appsflyer.com", "branch.io", "adjust.com", "kochava.com"
    )

    private val blockedPathParts = setOf(
        "/ads/", "/ad/", "/adserver", "/advert", "/banner", "/banners/", "/prebid",
        "/analytics", "/tracking", "/track/", "/pixel", "/beacon", "/telemetry"
    )

    private val outsideSchemes = setOf("intent", "market", "mailto", "tel", "sms")

    fun shouldBlock(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase().orEmpty()
        if (scheme !in setOf("http", "https")) return false
        val host = parsed.host?.lowercase().orEmpty()
        val path = parsed.encodedPath?.lowercase().orEmpty()
        return blockedHostParts.any { host.contains(it) } || blockedPathParts.any { path.contains(it) }
    }

    fun shouldOpenOutside(url: String?): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull().orEmpty()
        return scheme in outsideSchemes
    }

    fun normalizeAddress(input: String): String {
        val text = input.trim()
        if (text.isBlank()) return HOME_URL
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
        val looksLikeDomain = text.contains('.') && !text.contains(' ')
        return if (looksLikeDomain) "https://$text" else "https://www.google.com/search?safe=off&q=${Uri.encode(text)}"
    }

    const val HOME_URL = "https://www.google.com/"
}
