package com.steadyvault.camera.ui.browser

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object InstagramSessionMediaResolver {
    private const val FALLBACK_DOC_ID = "27128499623469141"
    private const val APP_ID = "936619743392459"

    data class Media(val url: String, val mimeType: String = "video/mp4")

    fun resolve(
        pageUrl: String,
        shortcodeHint: String,
        cookie: String,
        userAgent: String,
        csrfToken: String,
        fbDtsg: String,
        docId: String
    ): Media {
        require(InstagramPublicAccess.hasAuthenticatedSession(cookie)) { "Entre no Instagram pelo SteadyVault antes de baixar" }
        val shortcode = shortcodeHint.ifBlank { InstagramPublicAccess.mediaShortcode(pageUrl).orEmpty() }
        require(shortcode.isNotBlank()) { "Não foi possível identificar o Reel aberto" }
        val ids = listOf(docId, FALLBACK_DOC_ID).filter(String::isNotBlank).distinct()
        val endpoints = listOf("https://www.instagram.com/api/graphql", "https://www.instagram.com/graphql/query")
        var lastError: Throwable? = null
        for (id in ids) for (endpoint in endpoints) {
            runCatching { requestGraphql(endpoint, pageUrl, shortcode, cookie, userAgent, csrfToken, fbDtsg, id) }
                .onSuccess { media -> if (media != null) return media }
                .onFailure { lastError = it }
        }
        runCatching { requestLegacyJson(pageUrl, shortcode, cookie, userAgent, csrfToken) }
            .onSuccess { media -> if (media != null) return media }
            .onFailure { lastError = it }
        throw IllegalStateException(lastError?.message ?: "O Instagram não entregou o arquivo do Reel para esta sessão")
    }

    private fun requestGraphql(
        endpoint: String,
        pageUrl: String,
        shortcode: String,
        cookie: String,
        userAgent: String,
        csrfToken: String,
        fbDtsg: String,
        docId: String
    ): Media? {
        val variables = JSONObject()
            .put("shortcode", shortcode)
            .put("__relay_internal__pv__PolarisAIGMMediaWebLabelEnabledrelayprovider", false)
            .toString()
        val fields = linkedMapOf<String, String>()
        if (fbDtsg.isNotBlank()) fields["fb_dtsg"] = fbDtsg
        fields["variables"] = variables
        fields["doc_id"] = docId
        val body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Origin", "https://www.instagram.com")
            setRequestProperty("Referer", pageUrl)
            setRequestProperty("X-IG-App-ID", APP_ID)
            setRequestProperty("X-Requested-With", "XMLHttpRequest")
            setRequestProperty("X-FB-Friendly-Name", "PolarisPostRootQuery")
            if (csrfToken.isNotBlank()) setRequestProperty("X-CSRFToken", csrfToken)
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "Instagram retornou HTTP $code" }
            findBestVideo(JSONObject(response))
        } finally {
            connection.disconnect()
        }
    }

    private fun requestLegacyJson(
        pageUrl: String,
        shortcode: String,
        cookie: String,
        userAgent: String,
        csrfToken: String
    ): Media? {
        val urls = listOf(
            "https://www.instagram.com/reel/$shortcode/?__a=1&__d=dis",
            "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
        )
        for (url in urls) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Cookie", cookie)
                setRequestProperty("Referer", pageUrl)
                setRequestProperty("X-IG-App-ID", APP_ID)
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                if (csrfToken.isNotBlank()) setRequestProperty("X-CSRFToken", csrfToken)
            }
            try {
                if (connection.responseCode !in 200..299) continue
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                runCatching { findBestVideo(JSONObject(text)) }.getOrNull()?.let { return it }
                extractVideoUrlFromText(text)?.let { return Media(it) }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun findBestVideo(root: Any?): Media? {
        val candidates = mutableListOf<Triple<String, Int, Int>>()
        collectVideos(root, candidates)
        val best = candidates
            .filter { it.first.startsWith("http://", true) || it.first.startsWith("https://", true) }
            .maxByOrNull { (_, width, height) -> width.toLong() * height.toLong() }
            ?: return null
        return Media(best.first)
    }

    private fun collectVideos(value: Any?, out: MutableList<Triple<String, Int, Int>>) {
        when (value) {
            is JSONObject -> {
                value.optJSONArray("video_versions")?.let { versions ->
                    for (i in 0 until versions.length()) {
                        val item = versions.optJSONObject(i) ?: continue
                        val url = item.optString("url")
                        if (url.isNotBlank()) out += Triple(url, item.optInt("width"), item.optInt("height"))
                    }
                }
                val direct = value.optString("video_url")
                if (direct.isNotBlank()) out += Triple(direct, value.optInt("dimensions_width"), value.optInt("dimensions_height"))
                val keys = value.keys()
                while (keys.hasNext()) collectVideos(value.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until value.length()) collectVideos(value.opt(i), out)
        }
    }

    private fun extractVideoUrlFromText(text: String): String? {
        val normalized = text.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("\\\"video_url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"")
            .find(normalized)?.groupValues?.getOrNull(1)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
