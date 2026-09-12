from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    return text.replace(old, new, 1)

# BrowserWebViewConfigurator: isolate Instagram from ordinary/private browsing with WebView profiles.
Path('app/src/main/java/com/steadyvault/camera/ui/browser/BrowserWebViewConfigurator.kt').write_text('''package com.steadyvault.camera.ui.browser

import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

object BrowserWebViewConfigurator {
    const val PROFILE_PRIVATE = "steadyvault_private"
    const val PROFILE_INSTAGRAM = "steadyvault_instagram"

    data class Environment(
        val defaultUserAgent: String,
        val webViewPackage: String,
        val webViewVersion: String
    )

    fun supportsProfiles(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun profileForUrl(url: String): String =
        if (InstagramPublicAccess.isInstagramUrl(url)) PROFILE_INSTAGRAM else PROFILE_PRIVATE

    fun configure(
        webView: WebView,
        mixedContentCompatibility: Boolean,
        thirdPartyCookies: Boolean,
        profileName: String = PROFILE_PRIVATE
    ): Environment {
        if (supportsProfiles()) WebViewCompat.setProfile(webView, profileName)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = if (mixedContentCompatibility) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.safeBrowsingEnabled = true
        settings.saveFormData = false
        webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        }

        WebView.setWebContentsDebuggingEnabled(false)

        cookieManager(webView).apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, thirdPartyCookies)
        }

        val packageInfo = WebViewCompat.getCurrentWebViewPackage(webView.context.applicationContext)
        return Environment(
            defaultUserAgent = settings.userAgentString.orEmpty(),
            webViewPackage = packageInfo?.packageName.orEmpty(),
            webViewVersion = packageInfo?.versionName.orEmpty()
        )
    }

    fun cookieManager(webView: WebView): CookieManager =
        if (supportsProfiles()) WebViewCompat.getProfile(webView).cookieManager else CookieManager.getInstance()

    fun currentUserAgent(context: android.content.Context): String =
        WebSettings.getDefaultUserAgent(context.applicationContext).orEmpty()

    fun desktopUserAgent(defaultUserAgent: String): String = defaultUserAgent
        .replace("; wv", "", ignoreCase = true)
        .replace(" Mobile ", " ", ignoreCase = true)
        .replace(" Mobile", "", ignoreCase = true)
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    fun resetPrivateProfile() {
        if (!supportsProfiles()) return
        runCatching { ProfileStore.getInstance().deleteProfile(PROFILE_PRIVATE) }
    }

    fun clearPrivateData(webView: WebView, onDone: (() -> Unit)? = null) {
        webView.clearHistory()
        webView.clearCache(true)

        val cookies = cookieManager(webView)
        val finish = Runnable {
            cookies.removeAllCookies {
                cookies.flush()
                onDone?.invoke()
            }
        }

        if (supportsProfiles()) {
            runCatching { WebViewCompat.getProfile(webView).webStorage.deleteAllData() }
            finish.run()
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            val uiExecutor = Executor { command -> webView.post(command) }
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), uiExecutor, finish)
        } else {
            WebStorage.getInstance().deleteAllData()
            finish.run()
        }
    }
}
''')

# Instagram URL rules: keep query params and recognize Story URLs without pretending they are posts/reels.
Path('app/src/main/java/com/steadyvault/camera/ui/browser/InstagramPublicAccess.kt').write_text('''package com.steadyvault.camera.ui.browser

import java.net.URI
import java.util.Locale

internal object InstagramPublicAccess {
    fun isInstagramUrl(value: String): Boolean = runCatching {
        val host = URI(value.trim()).host.orEmpty().lowercase(Locale.US)
        host == "instagram.com" || host.endsWith(".instagram.com")
    }.getOrDefault(false)

    fun isStoryUrl(value: String): Boolean = runCatching {
        if (!isInstagramUrl(value)) return false
        val parts = URI(value.trim()).path.orEmpty().split('/').filter(String::isNotBlank)
        parts.firstOrNull()?.equals("stories", ignoreCase = true) == true && parts.size >= 2
    }.getOrDefault(false)

    fun normalize(value: String): String {
        val trimmed = value.trim()
        if (!isInstagramUrl(trimmed)) return trimmed
        return runCatching {
            val uri = URI(trimmed)
            val path = uri.path.orEmpty().ifBlank { "/" }
            URI("https", "www.instagram.com", path, uri.rawQuery, null).toASCIIString()
        }.getOrDefault(trimmed)
    }

    fun fallbackUrls(value: String): List<String> = runCatching {
        if (!isInstagramUrl(value) || isStoryUrl(value)) return emptyList()
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
        if (isStoryUrl(normalized)) return listOf(normalized)
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
''')

# SocialMediaDownloader: accept cookies from the WebView profile instead of assuming the default cookie jar.
p = Path('app/src/main/java/com/steadyvault/camera/ui/browser/SocialMediaDownloader.kt')
text = p.read_text()
text = replace_once(text,
'''        val useBrowserSession: Boolean = true,
        val thumbnailUrl: String = ""
''',
'''        val useBrowserSession: Boolean = true,
        val thumbnailUrl: String = "",
        val browserCookieHeader: String = ""
''', 'AnalyzedMedia cookies')
text = replace_once(text,
'''    fun analyze(context: Context, rawUrl: String): AnalyzedMedia {
        val url = InstagramPublicAccess.normalize(rawUrl)
        require(isValidHttpUrl(url)) { "Informe um link http/https válido" }
        SteadyVaultApplication.ensureMediaEngine(context.applicationContext as Application, refreshExtractor = true)
        if (InstagramPublicAccess.isInstagramUrl(url)) return analyzeInstagram(context, url)
        return try {
            analyzeOnce(context, url, useBrowserSession = true)
        } catch (error: Throwable) {
            throw IllegalStateException(cleanError(error), error)
        }
    }

    private fun analyzeInstagram(context: Context, url: String): AnalyzedMedia {
        var lastError = try {
            return analyzeOnce(context, url, useBrowserSession = false)
        } catch (error: Throwable) {
            error
        }

        val application = context.applicationContext as Application
        if (SteadyVaultApplication.repairMediaExtractorForCompatibility(application)) {
            try {
                return analyzeOnce(context, url, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        for (candidate in InstagramPublicAccess.fallbackUrls(url)) {
            try {
                return analyzeOnce(context, candidate, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        analyzeInstagramPublicPage(context, url)?.let { return it }

        if (hasInstagramSession(url)) {
            for (candidate in listOf(url) + InstagramPublicAccess.fallbackUrls(url)) {
                try {
                    return analyzeOnce(context, candidate, useBrowserSession = true)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        throw IllegalStateException(cleanInstagramError(lastError), lastError)
    }
''',
'''    fun analyze(context: Context, rawUrl: String, browserCookieHeader: String = ""): AnalyzedMedia {
        val url = InstagramPublicAccess.normalize(rawUrl)
        require(isValidHttpUrl(url)) { "Informe um link http/https válido" }
        SteadyVaultApplication.ensureMediaEngine(context.applicationContext as Application, refreshExtractor = true)
        if (InstagramPublicAccess.isInstagramUrl(url)) return analyzeInstagram(context, url, browserCookieHeader)
        return try {
            analyzeOnce(context, url, useBrowserSession = true, browserCookieHeader = browserCookieHeader)
        } catch (error: Throwable) {
            throw IllegalStateException(cleanError(error), error)
        }
    }

    private fun analyzeInstagram(context: Context, url: String, browserCookieHeader: String): AnalyzedMedia {
        var lastError: Throwable = IllegalStateException("O Instagram não disponibilizou a mídia")
        val authenticated = InstagramPublicAccess.hasAuthenticatedSession(browserCookieHeader)

        if (InstagramPublicAccess.isStoryUrl(url) && authenticated) {
            try {
                return analyzeOnce(context, url, useBrowserSession = true, browserCookieHeader = browserCookieHeader)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        try {
            return analyzeOnce(context, url, useBrowserSession = false)
        } catch (error: Throwable) {
            lastError = error
        }

        val application = context.applicationContext as Application
        if (SteadyVaultApplication.repairMediaExtractorForCompatibility(application)) {
            try {
                return analyzeOnce(context, url, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        for (candidate in InstagramPublicAccess.fallbackUrls(url)) {
            try {
                return analyzeOnce(context, candidate, useBrowserSession = false)
            } catch (error: Throwable) {
                lastError = error
            }
        }

        analyzeInstagramPublicPage(context, url)?.let { return it }

        if (authenticated) {
            for (candidate in listOf(url) + InstagramPublicAccess.fallbackUrls(url)) {
                try {
                    return analyzeOnce(context, candidate, useBrowserSession = true, browserCookieHeader = browserCookieHeader)
                } catch (error: Throwable) {
                    lastError = error
                }
            }
        }

        throw IllegalStateException(cleanInstagramError(lastError), lastError)
    }
''', 'analyze Instagram')
text = replace_once(text,
'''            cookieFile = if (media.useBrowserSession) buildCookiesFile(app, media.sourceUrl) else null
''',
'''            cookieFile = if (media.useBrowserSession) buildCookiesFile(app, media.sourceUrl, media.browserCookieHeader) else null
''', 'download cookies')
text = replace_once(text,
'''    private fun analyzeOnce(context: Context, url: String, useBrowserSession: Boolean): AnalyzedMedia {
        val app = context.applicationContext
        val cookieFile = if (useBrowserSession) buildCookiesFile(app, url) else null
''',
'''    private fun analyzeOnce(
        context: Context,
        url: String,
        useBrowserSession: Boolean,
        browserCookieHeader: String = ""
    ): AnalyzedMedia {
        val app = context.applicationContext
        val cookieFile = if (useBrowserSession) buildCookiesFile(app, url, browserCookieHeader) else null
''', 'analyzeOnce signature')
text = replace_once(text,
'''                useBrowserSession = useBrowserSession,
                thumbnailUrl = thumbnailUrlFromInfo(info)
''',
'''                useBrowserSession = useBrowserSession,
                thumbnailUrl = thumbnailUrlFromInfo(info),
                browserCookieHeader = if (useBrowserSession) browserCookieHeader else ""
''', 'analyzeOnce result cookies')
text = replace_once(text,
'''    private fun buildCookiesFile(context: Context, url: String): File? {
        val cookieHeader = CookieManager.getInstance().getCookie(url).orEmpty()
''',
'''    private fun buildCookiesFile(context: Context, url: String, browserCookieHeader: String = ""): File? {
        val cookieHeader = browserCookieHeader.ifBlank { CookieManager.getInstance().getCookie(url).orEmpty() }
''', 'build cookie file')
text = replace_once(text,
'''    private fun hasInstagramSession(url: String): Boolean = InstagramPublicAccess.hasAuthenticatedSession(
        CookieManager.getInstance().getCookie(url).orEmpty()
    )

''', '', 'remove global Instagram session check')
p.write_text(text)

# PrivateBrowserActivity: switch isolated profiles by top-level host and add a one-tap Story download button.
p = Path('app/src/main/java/com/steadyvault/camera/ui/browser/PrivateBrowserActivity.kt')
text = p.read_text()
text = text.replace('import android.webkit.CookieManager\n', '')
text = replace_once(text,
'''    private lateinit var progress: ProgressBar
    private lateinit var customContainer: FrameLayout
''',
'''    private lateinit var progress: ProgressBar
    private lateinit var customContainer: FrameLayout
    private lateinit var storyDownload: TextView
''', 'story field')
text = replace_once(text,
'''    private var pageLoading = false
    private var lastRequestedPageUrl = BrowserBlocker.HOME_URL
''',
'''    private var pageLoading = false
    private var lastRequestedPageUrl = BrowserBlocker.HOME_URL
    private var currentStoryUrl = ""
    private var webProfileName = BrowserWebViewConfigurator.PROFILE_PRIVATE
''', 'profile fields')
text = replace_once(text,
'''        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_private_browser)
''',
'''        super.onCreate(savedInstanceState)
        BrowserWebViewConfigurator.resetPrivateProfile()
        setContentView(R.layout.activity_private_browser)
''', 'reset private startup')
text = replace_once(text,
'''        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, PrivateBrowserStore.thirdPartyCookiesEnabled(this))
''',
'''        BrowserWebViewConfigurator.cookieManager(webView)
            .setAcceptThirdPartyCookies(webView, PrivateBrowserStore.thirdPartyCookiesEnabled(this))
''', 'resume cookies')
text = replace_once(text,
'''        runCatching {
            webView.stopLoading()
            clearPrivateSession()
            webView.destroy()
        }
        downloadExecutor.shutdownNow()
''',
'''        runCatching {
            webView.stopLoading()
            if (!BrowserWebViewConfigurator.supportsProfiles()) clearPrivateSession()
            webView.destroy()
        }
        BrowserWebViewConfigurator.resetPrivateProfile()
        downloadExecutor.shutdownNow()
''', 'destroy isolation')
text = replace_once(text,
'''        customContainer = findViewById(R.id.browserFullscreenContainer)
    }

    private fun recoverWebViewAfterRendererGone''',
'''        customContainer = findViewById(R.id.browserFullscreenContainer)
        storyDownload = findViewById(R.id.browserStoryDownload)
    }

    private fun browserCookie(url: String): String =
        BrowserWebViewConfigurator.cookieManager(webView).getCookie(url).orEmpty()

    private fun loadIsolatedUrl(url: String) {
        if (url.isBlank()) return
        val targetProfile = BrowserWebViewConfigurator.profileForUrl(url)
        if (BrowserWebViewConfigurator.supportsProfiles() && targetProfile != webProfileName) {
            switchWebViewProfile(targetProfile, url)
            return
        }
        rememberRequestedPage(url, allowLoginWall = true)
        webView.loadUrl(url)
    }

    private fun switchWebViewProfile(targetProfile: String, url: String) {
        if (customView != null) hideCustomView()
        val oldWebView = webView
        val parent = oldWebView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(oldWebView).coerceAtLeast(0)
        val params = oldWebView.layoutParams
        val oldProfile = webProfileName
        oldWebView.stopLoading()
        parent.removeView(oldWebView)
        runCatching { oldWebView.destroy() }
        if (oldProfile == BrowserWebViewConfigurator.PROFILE_PRIVATE || targetProfile == BrowserWebViewConfigurator.PROFILE_PRIVATE) {
            BrowserWebViewConfigurator.resetPrivateProfile()
        }
        webProfileName = targetProfile
        webView = WebView(this).apply {
            id = R.id.browserWebView
            layoutParams = params
        }
        parent.addView(webView, index)
        detectedMediaUrls.clear()
        configureWebView()
        applySecurityFlag()
        rememberRequestedPage(url, allowLoginWall = true)
        webView.loadUrl(url)
    }

    private fun updateStoryDownload(url: String) {
        val storyUrl = url.takeIf(InstagramPublicAccess::isStoryUrl).orEmpty()
        if (storyUrl.isNotBlank() && storyUrl != currentStoryUrl) {
            currentStoryUrl = storyUrl
            detectedMediaUrls.clear()
        } else if (storyUrl.isBlank()) {
            currentStoryUrl = ""
        }
        storyDownload.visibility = if (storyUrl.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun downloadCurrentInstagramStory() {
        val storyUrl = sequenceOf(webView.url.orEmpty(), currentStoryUrl, lastRequestedPageUrl)
            .firstOrNull(InstagramPublicAccess::isStoryUrl)
            .orEmpty()
        if (storyUrl.isBlank()) {
            Toast.makeText(this, "Abra um Story antes de baixar", Toast.LENGTH_SHORT).show()
            return
        }
        val capturedStoryUrl = storyUrl
        webView.evaluateJavascript(STORY_MEDIA_SCAN_SCRIPT) { raw ->
            val result = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            val directUrl = result?.optString("url").orEmpty()
            val mimeType = result?.optString("mimeType").orEmpty()
            if (directUrl.startsWith("http://", true) || directUrl.startsWith("https://", true)) {
                downloadStoryDirect(directUrl, mimeType, capturedStoryUrl)
            } else {
                resolveInstagramStoryForDownload(capturedStoryUrl)
            }
        }
    }

    private fun downloadStoryDirect(url: String, mimeType: String, storyUrl: String) {
        val userAgent = webView.settings.userAgentString.orEmpty()
        val effectiveMime = mimeType.ifBlank { BrowserVaultDownloader.mimeHintForUrl(url) }
        val fileName = URLUtil.guessFileName(url, "", effectiveMime).ifBlank { "Instagram_Story" }
        val destination = PrivateBrowserStore.downloadDestination(this)
        if (destination == PrivateBrowserStore.DESTINATION_DOWNLOADS) {
            downloadToAndroidDownloads(url, userAgent, "", effectiveMime, fileName, storyUrl)
        } else {
            downloadToVault(url, userAgent, "", effectiveMime, destination, storyUrl)
        }
    }

    private fun resolveInstagramStoryForDownload(storyUrl: String) {
        val cookie = browserCookie(storyUrl)
        Toast.makeText(this, "Preparando Story para download…", Toast.LENGTH_SHORT).show()
        downloadExecutor.execute {
            val media = runCatching { SocialMediaDownloader.analyze(this, storyUrl, cookie) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                media.onSuccess { analyzed ->
                    val format = analyzed.formats.firstOrNull()
                    if (format == null) {
                        Toast.makeText(this, "Nenhuma mídia disponível nesse Story", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    val stored = PrivateBrowserStore.downloadDestination(this)
                    val destination = if (stored == PrivateBrowserStore.DESTINATION_DOWNLOADS) {
                        PrivateBrowserStore.DESTINATION_PRIMARY
                    } else stored
                    downloadSocialMedia(analyzed, format, destination)
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Não foi possível baixar esse Story", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun recoverWebViewAfterRendererGone''', 'insert isolation/story helpers')
text = replace_once(text,
'''        webView = WebView(this).apply {
            id = R.id.browserWebView
            layoutParams = params
        }
        parent.addView(webView, index)
        configureWebView()
''',
'''        webProfileName = BrowserWebViewConfigurator.profileForUrl(restoreUrl)
        if (webProfileName == BrowserWebViewConfigurator.PROFILE_PRIVATE) BrowserWebViewConfigurator.resetPrivateProfile()
        webView = WebView(this).apply {
            id = R.id.browserWebView
            layoutParams = params
        }
        parent.addView(webView, index)
        configureWebView()
''', 'renderer profile')
text = replace_once(text,
'''            mixedContentCompatibility = PrivateBrowserStore.mixedContentCompatibility(this),
            thirdPartyCookies = PrivateBrowserStore.thirdPartyCookiesEnabled(this)
        )
''',
'''            mixedContentCompatibility = PrivateBrowserStore.mixedContentCompatibility(this),
            thirdPartyCookies = PrivateBrowserStore.thirdPartyCookiesEnabled(this),
            profileName = webProfileName
        )
''', 'configure profile')
text = replace_once(text,
'''                if (BrowserBlocker.shouldOpenOutside(url)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return true
                }
                return false
''',
'''                if (BrowserBlocker.shouldOpenOutside(url)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return true
                }
                val targetProfile = BrowserWebViewConfigurator.profileForUrl(url)
                if (BrowserWebViewConfigurator.supportsProfiles() && targetProfile != webProfileName) {
                    loadIsolatedUrl(url)
                    return true
                }
                return false
''', 'navigation profile switch')
text = replace_once(text,
'''                address.setText(url.orEmpty())
                subtitle.text = "Carregando…"
''',
'''                address.setText(url.orEmpty())
                updateStoryDownload(url.orEmpty())
                subtitle.text = "Carregando…"
''', 'page start story')
text = replace_once(text,
'''                address.setText(url.orEmpty())
                updatePageLabels()
                progress.visibility = View.GONE
            }

            override fun onReceivedError''',
'''                address.setText(url.orEmpty())
                updateStoryDownload(url.orEmpty())
                updatePageLabels()
                progress.visibility = View.GONE
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                val current = url.orEmpty()
                rememberRequestedPage(current, allowLoginWall = false)
                address.setText(current)
                updateStoryDownload(current)
            }

            override fun onReceivedError''', 'history story tracking')
text = replace_once(text,
'''        findViewById<View>(R.id.browserHome).setOnClickListener { webView.loadUrl(BrowserBlocker.HOME_URL) }
        findViewById<View>(R.id.browserMenu).setOnClickListener { showBrowserMenu() }
''',
'''        findViewById<View>(R.id.browserHome).setOnClickListener { loadIsolatedUrl(BrowserBlocker.HOME_URL) }
        findViewById<View>(R.id.browserMenu).setOnClickListener { showBrowserMenu() }
        storyDownload.setOnClickListener { downloadCurrentInstagramStory() }
''', 'bind story action')
text = replace_once(text,
'''        rememberRequestedPage(normalized, allowLoginWall = true)
        webView.loadUrl(normalized)
''',
'''        loadIsolatedUrl(normalized)
''', 'address isolated load')
text = replace_once(text,
'''        address.setText(url)
        rememberRequestedPage(url, allowLoginWall = true)
        webView.loadUrl(url)
        return true
''',
'''        address.setText(url)
        loadIsolatedUrl(url)
        return true
''', 'shared isolated load')
text = replace_once(text,
'''                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !cookies)
''',
'''                    BrowserWebViewConfigurator.cookieManager(webView).setAcceptThirdPartyCookies(webView, !cookies)
''', 'menu cookies')
text = replace_once(text,
'''        ) { index -> webView.loadUrl(favorites[index].url) }
''',
'''        ) { index -> loadIsolatedUrl(favorites[index].url) }
''', 'favorites isolation')
text = replace_once(text,
'''        val current = webView.url.orEmpty()
        return when {
            BrowserMediaExtractor.isExtractorPage(lastRequestedPageUrl) -> lastRequestedPageUrl
            BrowserMediaExtractor.isExtractorPage(current) -> current
            else -> current.ifBlank { lastRequestedPageUrl }
        }
''',
'''        val current = webView.url.orEmpty()
        return when {
            InstagramPublicAccess.isStoryUrl(current) -> current
            InstagramPublicAccess.isStoryUrl(lastRequestedPageUrl) -> lastRequestedPageUrl
            BrowserMediaExtractor.isExtractorPage(current) -> current
            BrowserMediaExtractor.isExtractorPage(lastRequestedPageUrl) -> lastRequestedPageUrl
            else -> current.ifBlank { lastRequestedPageUrl }
        }
''', 'current extractor priority')
text = replace_once(text,
'''        downloadExecutor.execute {
            val media = runCatching { SocialMediaDownloader.analyze(this, pageUrl) }
''',
'''        val cookie = browserCookie(pageUrl)
        downloadExecutor.execute {
            val media = runCatching { SocialMediaDownloader.analyze(this, pageUrl, cookie) }
''', 'social analyze cookies')
text = replace_once(text,
'''                cookie = CookieManager.getInstance().getCookie(it).orEmpty(),
''',
'''                cookie = browserCookie(it),
''', 'thumbnail cookies')
text = replace_once(text,
'''        val cookie = CookieManager.getInstance().getCookie(pageUrl).orEmpty()
''',
'''        val cookie = browserCookie(pageUrl)
''', 'page extractor cookies')
text = replace_once(text,
'''                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
''',
'''                .addRequestHeader("Cookie", browserCookie(url))
''', 'download manager cookies')
text = replace_once(text,
'''        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
''',
'''        val cookie = browserCookie(url)
''', 'vault cookies')
text = replace_once(text,
'''    private fun clearPrivateSession() {
        runCatching { BrowserWebViewConfigurator.clearPrivateData(webView) }
    }
''',
'''    private fun clearPrivateSession() {
        runCatching { BrowserWebViewConfigurator.clearPrivateData(webView) }
    }
''', 'clear helper remains')
text = replace_once(text,
'''        private const val MAX_IMAGE_ALTERNATIVES = 8
        private val MEDIA_SCAN_SCRIPT = """
''',
'''        private const val MAX_IMAGE_ALTERNATIVES = 8
        private val STORY_MEDIA_SCAN_SCRIPT = """
            (function() {
                let best = null;
                document.querySelectorAll('video,img').forEach(function(node) {
                    const rect = node.getBoundingClientRect();
                    const style = window.getComputedStyle(node);
                    if (rect.width < 80 || rect.height < 80 || rect.bottom <= 0 || rect.right <= 0 ||
                        rect.top >= window.innerHeight || rect.left >= window.innerWidth ||
                        style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity || 1) <= 0) return;
                    const isVideo = node.tagName.toLowerCase() === 'video';
                    const url = isVideo ? (node.currentSrc || node.src || '') : (node.currentSrc || node.src || '');
                    if (!url) return;
                    const area = Math.min(rect.width, window.innerWidth) * Math.min(rect.height, window.innerHeight);
                    if (!best || area > best.area) best = { url: url, mimeType: isVideo ? 'video/*' : 'image/*', area: area };
                });
                return best ? { url: best.url, mimeType: best.mimeType } : { url: '', mimeType: '' };
            })();
        """.trimIndent()
        private val MEDIA_SCAN_SCRIPT = """
''', 'story scan script')
p.write_text(text)

# Add the unobtrusive Story download button over the WebView.
p = Path('app/src/main/res/layout/activity_private_browser.xml')
text = p.read_text()
text = replace_once(text,
'''        <WebView
            android:id="@+id/browserWebView"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@color/background" />

        <FrameLayout
''',
'''        <WebView
            android:id="@+id/browserWebView"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@color/background" />

        <TextView
            android:id="@+id/browserStoryDownload"
            android:layout_width="wrap_content"
            android:layout_height="44dp"
            android:layout_gravity="end|bottom"
            android:layout_marginEnd="16dp"
            android:layout_marginBottom="16dp"
            android:background="@drawable/bg_oneui_button_primary"
            android:elevation="8dp"
            android:gravity="center"
            android:includeFontPadding="false"
            android:minWidth="92dp"
            android:paddingStart="16dp"
            android:paddingEnd="16dp"
            android:text="↓ Story"
            android:textColor="@color/background"
            android:textSize="14sp"
            android:textStyle="bold"
            android:visibility="gone" />

        <FrameLayout
''', 'story button layout')
p.write_text(text)
