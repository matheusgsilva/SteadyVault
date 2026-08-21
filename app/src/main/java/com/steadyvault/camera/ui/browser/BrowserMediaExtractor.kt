package com.steadyvault.camera.ui.browser

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal object BrowserMediaExtractor {
    private const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private const val MAX_REDIRECTS = 5

    data class Candidate(
        val url: String,
        val title: String,
        val subtitle: String,
        val mimeType: String = "",
        val referrer: String = "",
        val thumbnailUrl: String = ""
    )

    fun isExtractorPage(url: String?): Boolean {
        val lower = url?.lowercase(Locale.US).orEmpty()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        return listOf(
            "instagram.com/",
            "youtube.com/watch",
            "youtu.be/",
            "youtube.com/shorts/",
            "youtube.com/embed/",
            "x.com/",
            "twitter.com/",
            "tiktok.com/",
            "facebook.com/",
            "fb.watch/"
        ).any { lower.contains(it) }
    }

    fun resolve(
        pageUrl: String,
        userAgent: String,
        cookie: String,
        quality: String,
        resolution: String,
        onStatus: (String) -> Unit = {}
    ): List<Candidate> {
        val normalized = normalizePageUrl(pageUrl)
        if (normalized.isBlank()) return emptyList()
        val all = linkedMapOf<String, Candidate>()

        if (normalized.contains("youtu", ignoreCase = true)) {
            onStatus("Procurando arquivo de mídia público…")
            resolveYouTube(normalized, userAgent, cookie, quality, resolution).forEach { all[it.url] = it }
        }

        val instagramPage = InstagramPublicAccess.isInstagramUrl(normalized)
        val pageReferrer = if (instagramPage) INSTAGRAM_REFERRER else BrowserBlocker.HOME_URL
        val mobileAgent = userAgent.ifBlank { "Mozilla/5.0" }
        val desktopAgent = BrowserWebViewConfigurator.desktopUserAgent(mobileAgent)
        val agents = linkedSetOf(
            mobileAgent,
            desktopAgent,
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "Twitterbot/1.0"
        )
        for ((index, agent) in agents.withIndex()) {
            if (all.size >= 12) break
            onStatus(if (index == 0) "Lendo página pública…" else "Tentando metadados públicos…")
            val html = runCatching { fetchText(normalized, agent, cookie, pageReferrer) }.getOrNull().orEmpty()
            if (html.isNotBlank()) {
                val extracted = extractFromHtml(normalized, html, quality, resolution)
                extracted.forEach { all.putIfAbsent(it.url, it) }
                if (instagramPage && extracted.any(::isVideoCandidate)) break
            }
        }
        return all.values
            .filter { it.url.startsWith("http://", true) || it.url.startsWith("https://", true) }
            .filter { BrowserVaultDownloader.isLikelyMediaUrl(it.url, it.mimeType) }
            .distinctBy { it.url.substringBefore("#") }
            .take(20)
    }

    private fun resolveYouTube(pageUrl: String, userAgent: String, cookie: String, quality: String, resolution: String): List<Candidate> {
        val html = runCatching { fetchText(pageUrl, BrowserWebViewConfigurator.desktopUserAgent(userAgent.ifBlank { "Mozilla/5.0" }), cookie, BrowserBlocker.HOME_URL) }.getOrNull().orEmpty()
        if (html.isBlank()) return emptyList()
        val playerJson = extractBalancedJson(html, "ytInitialPlayerResponse") ?: return extractFromHtml(pageUrl, html, quality, resolution)
        val player = runCatching { JSONObject(playerJson) }.getOrNull() ?: return emptyList()
        val title = player.optJSONObject("videoDetails")?.optString("title").orEmpty().ifBlank { "Vídeo" }
        val thumbnailUrl = youtubeThumbnail(player)
        val formats = mutableListOf<JSONObject>()
        player.optJSONObject("streamingData")?.let { streaming ->
            streaming.optJSONArray("formats")?.appendTo(formats)
            streaming.optJSONArray("adaptiveFormats")?.appendTo(formats)
        }
        val candidates = formats.mapNotNull { format ->
            val direct = format.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mime = format.optString("mimeType").substringBefore(';')
            if (!mime.startsWith("video/", true)) return@mapNotNull null
            val height = format.optInt("height", 0)
            val label = format.optString("qualityLabel").ifBlank { if (height > 0) "${height}p" else "vídeo" }
            val bitrate = format.optLong("bitrate", 0L)
            val audio = format.has("audioQuality") || format.optString("audioQuality").isNotBlank()
            val subtitle = buildString {
                append(label)
                if (audio) append(" • com áudio") else append(" • vídeo")
                if (bitrate > 0L) append(" • ${bitrate / 1_000_000} Mbps")
            }
            Candidate(cleanJsonUrl(direct), title, subtitle, mime, pageUrl, thumbnailUrl)
        }.filter { BrowserVaultDownloader.isLikelyMediaUrl(it.url, it.mimeType) }
        return rankCandidates(candidates, quality, resolution)
    }

    private fun extractFromHtml(pageUrl: String, html: String, quality: String, resolution: String): List<Candidate> {
        val pageTitle = html.extractTitle().ifBlank { hostFor(pageUrl).ifBlank { "Mídia" } }
        val found = linkedMapOf<String, Candidate>()
        META_PROPERTY_FIRST_PATTERN.findAll(html).forEach { match ->
            val name = match.groupValues.getOrNull(1).orEmpty().lowercase(Locale.US)
            val value = htmlDecode(match.groupValues.getOrNull(2).orEmpty())
            val mime = when {
                name.contains("image") || value.contains("/image/", true) -> "image/*"
                name.contains("video") || value.contains("video", true) -> "video/*"
                else -> ""
            }
            addCandidate(found, pageUrl, value, pageTitle, name, mime)
        }
        META_CONTENT_FIRST_PATTERN.findAll(html).forEach { match ->
            val value = htmlDecode(match.groupValues.getOrNull(1).orEmpty())
            val name = match.groupValues.getOrNull(2).orEmpty().lowercase(Locale.US)
            val mime = when {
                name.contains("image") || value.contains("/image/", true) -> "image/*"
                name.contains("video") || value.contains("video", true) -> "video/*"
                else -> ""
            }
            addCandidate(found, pageUrl, value, pageTitle, name, mime)
        }
        JSON_URL_PATTERNS.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val key = match.groupValues.getOrNull(1).orEmpty()
                val value = cleanJsonUrl(match.groupValues.getOrNull(2).orEmpty())
                val mime = when {
                    key.contains("image", true) || value.contains("/image/", true) -> "image/*"
                    key.contains("video", true) || value.contains("video", true) -> "video/*"
                    else -> ""
                }
                addCandidate(found, pageUrl, value, pageTitle, key, mime)
            }
        }
        DIRECT_URL_PATTERN.findAll(html).forEach { match ->
            val raw = cleanJsonUrl(match.value)
            val mime = if (raw.contains("video", true) || raw.contains(".mp4", true)) "video/*" else "image/*"
            addCandidate(found, pageUrl, raw, pageTitle, "arquivo", mime)
        }
        val candidates = found.values.toList()
        val pagePreview = candidates.firstOrNull {
            isImageCandidate(it) && (
                it.subtitle.contains("og:image", true) ||
                    it.subtitle.contains("twitter:image", true) ||
                    it.subtitle.contains("thumbnail", true)
                )
        }?.url ?: candidates.firstOrNull(::isImageCandidate)?.url.orEmpty()
        return rankCandidates(
            candidates.map { candidate ->
                when {
                    isImageCandidate(candidate) -> candidate.copy(thumbnailUrl = candidate.url)
                    isVideoCandidate(candidate) && pagePreview.isNotBlank() -> candidate.copy(thumbnailUrl = pagePreview)
                    else -> candidate
                }
            },
            quality,
            resolution
        )
    }

    private fun addCandidate(
        map: LinkedHashMap<String, Candidate>,
        pageUrl: String,
        raw: String,
        title: String,
        source: String,
        mimeType: String
    ) {
        val url = normalizeCandidateUrl(raw, pageUrl)
        if (url.isBlank()) return
        if (!BrowserVaultDownloader.isLikelyMediaUrl(url, mimeType)) return
        if (url.startsWith("data:", true) || url.startsWith("blob:", true)) return
        val clean = url.substringBefore("#")
        val thumbnail = clean.takeIf { mimeType.startsWith("image/", true) }.orEmpty()
        map.putIfAbsent(clean, Candidate(clean, title, source.ifBlank { hostFor(clean) }, mimeType, pageUrl, thumbnail))
    }

    private fun rankCandidates(candidates: List<Candidate>, quality: String, resolution: String): List<Candidate> {
        val wantedHeight = when (resolution) {
            PrivateBrowserStore.RESOLUTION_2160 -> 2160
            PrivateBrowserStore.RESOLUTION_1440 -> 1440
            PrivateBrowserStore.RESOLUTION_1080 -> 1080
            PrivateBrowserStore.RESOLUTION_720 -> 720
            PrivateBrowserStore.RESOLUTION_480 -> 480
            else -> Int.MAX_VALUE
        }
        return candidates.distinctBy { it.url }.sortedWith(compareByDescending<Candidate> { scoreCandidate(it, wantedHeight, quality) })
    }

    private fun scoreCandidate(candidate: Candidate, wantedHeight: Int, quality: String): Int {
        val text = (candidate.url + " " + candidate.subtitle).lowercase(Locale.US)
        val height = Regex("(2160|1440|1080|720|480|360|240)p?").find(text)?.groupValues?.firstOrNull()?.toIntOrNull() ?: 0
        var score = 0
        if (candidate.mimeType.startsWith("video", true) || text.contains("video")) score += 2000
        if (text.contains("mp4")) score += 300
        if (text.contains("webm")) score += 150
        if (text.contains("m3u8")) score -= 500
        if (text.contains("com áudio")) score += 180
        if (height > 0) {
            score += if (wantedHeight == Int.MAX_VALUE) height else 1000 - kotlin.math.abs(wantedHeight - height)
        }
        score += when (quality) {
            PrivateBrowserStore.QUALITY_MAX -> 300
            PrivateBrowserStore.QUALITY_BALANCED -> if (height in 480..1080) 250 else 0
            PrivateBrowserStore.QUALITY_SMALL -> if (height in 240..720) 250 else 0
            else -> 0
        }
        return score
    }

    private fun fetchText(url: String, userAgent: String, cookie: String, referrer: String): String {
        val connection = openConnection(url, userAgent, cookie, referrer, accept = "text/html,application/xhtml+xml,application/json,*/*;q=0.8")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "HTTP $code" }
            val input = connection.inputStream.buffered()
            val buffer = ByteArray(MAX_HTML_BYTES)
            var copied = 0
            while (copied < buffer.size) {
                val read = input.read(buffer, copied, buffer.size - copied)
                if (read <= 0) break
                copied += read
            }
            input.close()
            val charsetName = connection.contentType?.substringAfter("charset=", "UTF-8")?.substringBefore(';')?.trim().orEmpty().ifBlank { "UTF-8" }
            return runCatching { String(buffer, 0, copied, java.nio.charset.Charset.forName(charsetName)) }.getOrElse { String(buffer, 0, copied) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun openConnection(url: String, userAgent: String, cookie: String, referrer: String, accept: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { index ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent.ifBlank { "Mozilla/5.0" })
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (!location.isNullOrBlank() && index < MAX_REDIRECTS) {
                    current = URL(current, location)
                    return@repeat
                }
            }
            return connection
        }
        throw IllegalStateException("Redirecionamentos demais")
    }

    private fun normalizeCandidateUrl(raw: String, pageUrl: String): String {
        val cleaned = cleanJsonUrl(htmlDecode(raw)).trim().trim('"', '\'', ' ')
        if (cleaned.isBlank()) return ""
        return runCatching { URL(URL(pageUrl), cleaned).toString() }.getOrDefault(cleaned)
    }

    fun normalizePageUrl(raw: String): String {
        val value = BrowserBlocker.normalizeAddress(raw).trim()
        if (value.isBlank()) return ""
        return if (InstagramPublicAccess.isInstagramUrl(value)) InstagramPublicAccess.normalize(value) else value
    }

    private fun isVideoCandidate(candidate: Candidate): Boolean {
        val value = "${candidate.url} ${candidate.mimeType} ${candidate.subtitle}".lowercase(Locale.US)
        return candidate.mimeType.startsWith("video/", true) ||
            value.contains("video") ||
            value.substringBefore('?').endsWith(".mp4") ||
            value.substringBefore('?').endsWith(".webm") ||
            value.substringBefore('?').endsWith(".mov")
    }

    private fun isImageCandidate(candidate: Candidate): Boolean {
        val value = "${candidate.url} ${candidate.mimeType}".lowercase(Locale.US).substringBefore('?')
        return candidate.mimeType.startsWith("image/", true) ||
            value.endsWith(".jpg") ||
            value.endsWith(".jpeg") ||
            value.endsWith(".png") ||
            value.endsWith(".webp") ||
            value.endsWith(".gif") ||
            value.endsWith(".heic") ||
            value.endsWith(".heif")
    }

    private fun youtubeThumbnail(player: JSONObject): String {
        val thumbnails = player.optJSONObject("videoDetails")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: return ""
        var selected = ""
        var selectedArea = -1L
        for (index in 0 until thumbnails.length()) {
            val item = thumbnails.optJSONObject(index) ?: continue
            val url = cleanJsonUrl(item.optString("url"))
            val area = item.optLong("width", 0L) * item.optLong("height", 0L)
            if (url.isNotBlank() && area >= selectedArea) {
                selected = url
                selectedArea = area
            }
        }
        return selected
    }

    private fun cleanJsonUrl(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u0025", "%")
        .replace("&amp;", "&")
        .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }

    private fun htmlDecode(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private fun String.extractTitle(): String = TITLE_PATTERN.find(this)?.groupValues?.getOrNull(1)?.let(::htmlDecode)?.trim().orEmpty()

    private fun JSONArray.appendTo(target: MutableList<JSONObject>) {
        for (i in 0 until length()) optJSONObject(i)?.let(target::add)
    }

    private fun extractBalancedJson(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until html.length) {
            val c = html[i]
            when {
                escape -> escape = false
                c == '\\' && inString -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun hostFor(url: String): String = runCatching { URL(url).host.removePrefix("www.") }.getOrDefault("")

    private val TITLE_PATTERN = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE)
    private val META_PROPERTY_FIRST_PATTERN = Regex("<meta[^>]+(?:property|name)=[\"']([^\"']*(?:video|image|thumbnail|contentUrl|embedUrl)[^\"']*)[\"'][^>]+content=[\"']([^\"']+)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val META_CONTENT_FIRST_PATTERN = Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+(?:property|name)=[\"']([^\"']*(?:video|image|thumbnail|contentUrl|embedUrl)[^\"']*)[\"'][^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val JSON_URL_PATTERNS = listOf(
        Regex("[\"'](video_url|videoUrl|contentUrl|playbackUrl|thumbnailUrl|display_url|displayUrl|src)[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("[\"'](url)[\"']\\s*:\\s*[\"'](https?:\\\\/\\\\/[^\"']+(?:mp4|webm|jpg|jpeg|png|webp|gif|heic|heif)[^\"']*)[\"']", RegexOption.IGNORE_CASE)
    )
    private val DIRECT_URL_PATTERN = Regex("https?:\\\\?/\\\\?/[^\"'<>\\s]+?(?:mp4|webm|mov|m4v|jpg|jpeg|png|webp|gif|heic|heif)(?:[^\"'<>\\s]*)", RegexOption.IGNORE_CASE)

    private const val INSTAGRAM_REFERRER = "https://www.instagram.com/"
}
