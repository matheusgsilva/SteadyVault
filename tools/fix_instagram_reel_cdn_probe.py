from pathlib import Path


def replace_once(path: str, old: str, new: str, label: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    p.write_text(text.replace(old, new, 1))

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''    fun isLikelyMediaUrl(url: String?, mimeType: String = ""): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.US)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        if (normalizedMime in NON_MEDIA_MIME_TYPES || normalizedMime.startsWith("text/") || normalizedMime.contains("javascript")) return false
        if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) return true
        if (isInstagramVideoCdnUrl(lower)) return true''',
    '''    fun isLikelyMediaUrl(url: String?, mimeType: String = ""): Boolean {
        if (url.isNullOrBlank()) return false
        val lower = url.lowercase(Locale.US)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        val normalizedMime = mimeType.substringBefore(';').trim().lowercase(Locale.US)
        if (normalizedMime in NON_MEDIA_MIME_TYPES || normalizedMime.startsWith("text/") || normalizedMime.contains("javascript")) return false
        if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) return true
        if (isInstagramCdnUrl(lower)) return true''',
    'capture all Instagram CDN media requests'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''    private fun isInstagramVideoCdnUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.contains("cdninstagram.com") && !lower.contains("fbcdn.net")) return false''',
    '''    fun isInstagramCdnUrl(url: String?): Boolean {
        val lower = url?.lowercase(Locale.US).orEmpty()
        return lower.startsWith("http://") || lower.startsWith("https://") &&
            (lower.contains("cdninstagram.com") || lower.contains("fbcdn.net"))
    }

    fun probeMediaMimeType(url: String, userAgent: String, cookie: String, referrer: String): String {
        if (!isInstagramCdnUrl(url)) return mimeHintForUrl(url)
        val connection = openConnection(url, userAgent, cookie, referrer, rangeProbe = true)
        return try {
            val code = connection.responseCode
            if (code !in 200..299) return ""
            val mime = connection.contentType?.substringBefore(';')?.trim().orEmpty()
            when {
                mime.startsWith("video/", true) || mime.startsWith("image/", true) -> mime
                isInstagramVideoCdnUrl(connection.url.toString()) -> "video/mp4"
                else -> mimeHintForUrl(connection.url.toString())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isInstagramVideoCdnUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        if (!lower.contains("cdninstagram.com") && !lower.contains("fbcdn.net")) return false''',
    'add Instagram CDN probe'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/BrowserVaultDownloader.kt',
    '''    private fun openConnection(url: String, userAgent: String, cookie: String, referrer: String): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { index ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "video/*,image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }''',
    '''    private fun openConnection(
        url: String,
        userAgent: String,
        cookie: String,
        referrer: String,
        rangeProbe: Boolean = false
    ): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS + 1) { index ->
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 45_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "video/*,image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
                if (rangeProbe) setRequestProperty("Range", "bytes=0-0")
                if (referrer.isNotBlank()) setRequestProperty("Referer", referrer)
                if (cookie.isNotBlank()) setRequestProperty("Cookie", cookie)
            }''',
    'range probe support'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''            val scan = parseMediaScanResult(raw)
            val detected = synchronized(detectedMediaUrls) { detectedMediaUrls.toList() }
            val direct = (scan.urls + detected)
                .map { it.trim() }''',
    '''            val scan = parseMediaScanResult(raw)
            val detected = synchronized(detectedMediaUrls) { detectedMediaUrls.toList() }
            val pageUrl = currentExtractorPageUrl()
            val intercepted = if (InstagramPublicAccess.isInstagramUrl(pageUrl)) detected.asReversed() else detected
            val direct = (scan.urls + intercepted)
                .map { it.trim() }''',
    'prefer newest Instagram requests'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''                .sortedByDescending(BrowserVaultDownloader::isLikelyVideoUrl)
                .take(MAX_MEDIA_CHOICES)
            val pageUrl = currentExtractorPageUrl()
            val embeddedExtractor = scan.extractorUrl.takeIf(SocialMediaDownloader::canHandle).orEmpty()''',
    '''                .sortedByDescending(BrowserVaultDownloader::isLikelyVideoUrl)
                .take(MAX_MEDIA_CHOICES)
            val embeddedExtractor = scan.extractorUrl.takeIf(SocialMediaDownloader::canHandle).orEmpty()''',
    'dedupe page URL declaration'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''                directOnly -> directFallback()
                InstagramPublicAccess.isInstagramUrl(pageUrl) && direct.any(BrowserVaultDownloader::isLikelyVideoUrl) -> directFallback()''',
    '''                directOnly -> directFallback()
                InstagramPublicAccess.isInstagramUrl(pageUrl) && direct.any(BrowserVaultDownloader::isInstagramCdnUrl) -> directFallback()
                InstagramPublicAccess.isInstagramUrl(pageUrl) && direct.any(BrowserVaultDownloader::isLikelyVideoUrl) -> directFallback()''',
    'probe Instagram CDN before yt-dlp'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''            val directCandidates = direct.map { url ->
                val mimeHint = BrowserVaultDownloader.mimeHintForUrl(url)
                BrowserMediaExtractor.Candidate(
                    url = url,
                    title = BrowserVaultDownloader.displayNameForUrl(url),
                    subtitle = BrowserVaultDownloader.hostForUrl(url),
                    mimeType = mimeHint,
                    referrer = referrer,
                    thumbnailUrl = if (mimeHint.startsWith("image/")) url else pageThumbnailUrl
                )
            }
            val merged = (extracted + directCandidates)''',
    '''            val instagramPage = InstagramPublicAccess.isInstagramUrl(pageUrl)
            val directCandidates = direct.mapNotNull { url ->
                val hint = BrowserVaultDownloader.mimeHintForUrl(url)
                val mime = if (instagramPage && BrowserVaultDownloader.isInstagramCdnUrl(url) && hint.isBlank()) {
                    runCatching {
                        BrowserVaultDownloader.probeMediaMimeType(url, userAgent, browserCookie(url), referrer)
                    }.getOrDefault("")
                } else hint
                if (instagramPage && BrowserVaultDownloader.isInstagramCdnUrl(url) && mime.isBlank()) return@mapNotNull null
                BrowserMediaExtractor.Candidate(
                    url = url,
                    title = BrowserVaultDownloader.displayNameForUrl(url),
                    subtitle = BrowserVaultDownloader.hostForUrl(url),
                    mimeType = mime,
                    referrer = referrer,
                    thumbnailUrl = if (mime.startsWith("image/")) url else pageThumbnailUrl
                )
            }
            val merged = (if (instagramPage) directCandidates + extracted else extracted + directCandidates)''',
    'probe CDN MIME and prioritize WebView media'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''            val videoCandidates = merged.filter {
                BrowserVaultDownloader.isLikelyVideoUrl(it.url) || it.mimeType.startsWith("video/", true)
            }
            val candidates = if (videoCandidates.isNotEmpty()) {''',
    '''            val videoCandidates = merged.filter {
                BrowserVaultDownloader.isLikelyVideoUrl(it.url) || it.mimeType.startsWith("video/", true)
            }
            if (instagramPage && videoCandidates.isNotEmpty()) {
                val candidate = videoCandidates.first()
                runOnUiThread {
                    progressDialog.dismiss()
                    if (!isFinishing && !isDestroyed) {
                        download(candidate.url, userAgent, "", candidate.mimeType, candidate.referrer.ifBlank { pageUrl })
                    }
                }
                return@execute
            }
            val candidates = if (videoCandidates.isNotEmpty()) {''',
    'download first current Instagram video automatically'
)

replace_once(
    'app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt',
    '''                document.querySelectorAll('a[href]').forEach(function(node) {
                    try {
                        const url = new URL(node.href, location.href);
                        if (/\\.(mp4|m4v|mov|webm|mkv|3gp|jpg|jpeg|png|webp|gif|heic|heif)$/i.test(url.pathname)) add(url.href);
                    } catch (e) {}
                });
                let extractorUrl = '';''',
    '''                document.querySelectorAll('a[href]').forEach(function(node) {
                    try {
                        const url = new URL(node.href, location.href);
                        if (/\\.(mp4|m4v|mov|webm|mkv|3gp|jpg|jpeg|png|webp|gif|heic|heif)$/i.test(url.pathname)) add(url.href);
                    } catch (e) {}
                });
                try {
                    performance.getEntriesByType('resource').slice(-160).reverse().forEach(function(entry) {
                        const candidate = String(entry.name || '');
                        if (/(cdninstagram\\.com|fbcdn\\.net)/i.test(candidate) || /\\.(mp4|m4v|mov|webm|m3u8|mpd)(?:$|[?#])/i.test(candidate)) add(candidate);
                    });
                } catch (e) {}
                let extractorUrl = '';''',
    'include recent WebView resource URLs'
)
