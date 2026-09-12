from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, found {count}")
    p.write_text(text.replace(old, new, 1))

access = Path('app/src/main/java/com/steadyvault/camera/ui/browser/InstagramPublicAccess.kt')
text = access.read_text()
needle = '''    fun isStoryUrl(value: String): Boolean = runCatching {\n        if (!isInstagramUrl(value)) return false\n        val parts = URI(value.trim()).path.orEmpty().split('/').filter(String::isNotBlank)\n        parts.firstOrNull()?.equals("stories", ignoreCase = true) == true && parts.size >= 2\n    }.getOrDefault(false)\n'''
insert = needle + '''\n    fun mediaShortcode(value: String): String? = runCatching {\n        if (!isInstagramUrl(value)) return@runCatching null\n        val parts = URI(value.trim()).path.orEmpty().split('/').filter(String::isNotBlank)\n        val index = parts.indexOfFirst { it.lowercase(Locale.US) in POST_TYPES }\n        parts.getOrNull(index + 1)?.takeIf { index >= 0 && it.isNotBlank() && !it.equals("audio", true) }\n    }.getOrNull()\n\n    fun isReelUrl(value: String): Boolean = runCatching {\n        if (!isInstagramUrl(value)) return@runCatching false\n        val parts = URI(value.trim()).path.orEmpty().split('/').filter(String::isNotBlank)\n        val index = parts.indexOfFirst { it.equals("reel", true) || it.equals("reels", true) }\n        index >= 0 && parts.getOrNull(index + 1)?.isNotBlank() == true\n    }.getOrDefault(false)\n'''
if needle not in text:
    raise SystemExit('InstagramPublicAccess story block not found')
access.write_text(text.replace(needle, insert, 1))

resolver = Path('app/src/main/java/com/steadyvault/camera/ui/browser/InstagramSessionMediaResolver.kt')
resolver.write_text(r'''package com.steadyvault.camera.ui.browser

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
''')

activity = 'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt'
replace_once(
    activity,
    '''    private data class PageMediaScan(\n        val urls: List<String>,\n        val thumbnailUrl: String,\n        val extractorUrl: String,\n        val hasVideoPlayer: Boolean,\n        val hasInternalStream: Boolean\n    )\n''',
    '''    private data class PageMediaScan(\n        val urls: List<String>,\n        val thumbnailUrl: String,\n        val extractorUrl: String,\n        val hasVideoPlayer: Boolean,\n        val hasInternalStream: Boolean\n    )\n\n    private data class InstagramSessionContext(\n        val shortcode: String = "",\n        val csrfToken: String = "",\n        val fbDtsg: String = "",\n        val docId: String = ""\n    )\n''',
    'session context data class'
)

replace_once(
    activity,
    '''    private fun scanPageMediaForDownload(directOnly: Boolean = false) {\n        webView.evaluateJavascript(MEDIA_SCAN_SCRIPT) { raw ->''',
    '''    private fun scanPageMediaForDownload(directOnly: Boolean = false) {\n        val requestedPageUrl = currentExtractorPageUrl()\n        if (!directOnly && InstagramPublicAccess.isInstagramUrl(requestedPageUrl) && !InstagramPublicAccess.isStoryUrl(requestedPageUrl)) {\n            resolveInstagramSessionMediaForDownload(requestedPageUrl) { scanPageMediaForDownload(directOnly = true) }\n            return\n        }\n        webView.evaluateJavascript(MEDIA_SCAN_SCRIPT) { raw ->''',
    'route Instagram through authenticated resolver'
)

marker = '''    private fun currentExtractorPageUrl(): String {\n'''
method = r'''    private fun resolveInstagramSessionMediaForDownload(pageUrl: String, onFailure: () -> Unit) {
        val progressDialog = OneUiDialog.progress(
            activity = this,
            title = "Procurando mídia",
            message = "Obtendo o arquivo pela sua sessão do Instagram…",
            cancelable = false
        )
        val userAgent = webView.settings.userAgentString.orEmpty()
        webView.evaluateJavascript(INSTAGRAM_SESSION_CONTEXT_SCRIPT) { raw ->
            val context = parseInstagramSessionContext(raw)
            val cookie = browserCookie(pageUrl)
            val shortcode = context.shortcode.ifBlank { InstagramPublicAccess.mediaShortcode(pageUrl).orEmpty() }
            if (shortcode.isBlank() || !InstagramPublicAccess.hasAuthenticatedSession(cookie)) {
                progressDialog.dismiss()
                onFailure()
                return@evaluateJavascript
            }
            downloadExecutor.execute {
                val media = runCatching {
                    InstagramSessionMediaResolver.resolve(
                        pageUrl = pageUrl,
                        shortcodeHint = shortcode,
                        cookie = cookie,
                        userAgent = userAgent,
                        csrfToken = context.csrfToken,
                        fbDtsg = context.fbDtsg,
                        docId = context.docId
                    )
                }
                runOnUiThread {
                    progressDialog.dismiss()
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    media.onSuccess {
                        download(it.url, userAgent, "", it.mimeType, pageUrl)
                    }.onFailure {
                        onFailure()
                    }
                }
            }
        }
    }

    private fun parseInstagramSessionContext(raw: String?): InstagramSessionContext = runCatching {
        val json = JSONObject(raw?.takeIf { it.isNotBlank() && it != "null" } ?: "{}")
        InstagramSessionContext(
            shortcode = json.optString("shortcode"),
            csrfToken = json.optString("csrfToken"),
            fbDtsg = json.optString("fbDtsg"),
            docId = json.optString("docId")
        )
    }.getOrDefault(InstagramSessionContext())

'''
replace_once(activity, marker, method + marker, 'authenticated resolver methods')

companion_marker = '''        private const val MAX_MEDIA_CHOICES = 40\n        private const val MAX_IMAGE_ALTERNATIVES = 8\n'''
script = r'''        private const val MAX_MEDIA_CHOICES = 40
        private const val MAX_IMAGE_ALTERNATIVES = 8
        private val INSTAGRAM_SESSION_CONTEXT_SCRIPT = """
            (function() {
                const KEY = '__steady_instagram_ctx';
                try {
                    sessionStorage.removeItem(KEY);
                    const bridge = document.createElement('script');
                    bridge.textContent = `(function(){
                        try {
                            let fb = '';
                            let doc = '';
                            try { fb = (typeof fb_dtsg !== 'undefined' && fb_dtsg) ? String(fb_dtsg) : ''; } catch (e) {}
                            try { doc = String(require('PolarisPostRootQuery').params.id || ''); } catch (e) {}
                            sessionStorage.setItem('__steady_instagram_ctx', JSON.stringify({fbDtsg: fb, docId: doc}));
                        } catch (e) {
                            sessionStorage.setItem('__steady_instagram_ctx', '{}');
                        }
                    })();`;
                    (document.documentElement || document.head || document.body).appendChild(bridge);
                    bridge.remove();
                } catch (e) {}
                let ctx = {};
                try { ctx = JSON.parse(sessionStorage.getItem(KEY) || '{}'); } catch (e) {}
                try { sessionStorage.removeItem(KEY); } catch (e) {}
                const csrf = document.cookie.match(/(?:^|;\s*)csrftoken=([^;]+)/);
                ctx.csrfToken = csrf ? decodeURIComponent(csrf[1]) : '';
                const parts = location.pathname.split('/').filter(Boolean);
                const mediaIndex = parts.findIndex(p => /^(reel|reels|p|tv)$/i.test(p));
                let shortcode = mediaIndex >= 0 ? (parts[mediaIndex + 1] || '') : '';
                if (!shortcode || /^audio$/i.test(shortcode)) {
                    let best = null;
                    document.querySelectorAll('video').forEach(function(video) {
                        const r = video.getBoundingClientRect();
                        if (r.width < 80 || r.height < 80 || r.bottom <= 0 || r.right <= 0 || r.top >= innerHeight || r.left >= innerWidth) return;
                        const area = Math.min(r.width, innerWidth) * Math.min(r.height, innerHeight);
                        if (!best || area > best.area) best = {node: video, area: area};
                    });
                    const scope = best && best.node.closest('article') ? best.node.closest('article') : document;
                    const link = scope.querySelector('a[href*="/reel/"],a[href*="/reels/"]');
                    if (link) {
                        try {
                            const p = new URL(link.href, location.href).pathname.split('/').filter(Boolean);
                            const i = p.findIndex(v => /^(reel|reels)$/i.test(v));
                            shortcode = i >= 0 ? (p[i + 1] || '') : shortcode;
                        } catch (e) {}
                    }
                }
                ctx.shortcode = shortcode || '';
                return ctx;
            })();
        """.trimIndent()
'''
replace_once(activity, companion_marker, script, 'Instagram session context script')
