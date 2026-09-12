package com.steadyvault.camera.ui.browser

import android.app.DownloadManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.steadyvault.camera.R
import com.steadyvault.camera.core.settings.CaptureSettings
import com.steadyvault.camera.ui.components.OneUiDialog
import com.steadyvault.camera.ui.navigation.BottomNavigation
import com.steadyvault.camera.ui.navigation.SystemBarInsets
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

class PrivateBrowserActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var customContainer: FrameLayout
    private lateinit var storyDownload: TextView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var defaultUserAgent = ""
    private val blockedCount = AtomicInteger(0)
    private var pageLoading = false
    private var lastRequestedPageUrl = BrowserBlocker.HOME_URL
    private var currentStoryUrl = ""
    private var webProfileName = BrowserWebViewConfigurator.PROFILE_PRIVATE
    private val detectedMediaUrls = Collections.synchronizedSet(LinkedHashSet<String>())
    private val downloadExecutor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            task.run()
        }, "SteadyVault-BrowserDownload")
    }

    private data class PageMediaScan(
        val urls: List<String>,
        val thumbnailUrl: String,
        val extractorUrl: String,
        val hasVideoPlayer: Boolean,
        val hasInternalStream: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrowserWebViewConfigurator.resetPrivateProfile()
        setContentView(R.layout.activity_private_browser)
        SystemBarInsets.applyTop(findViewById(R.id.browserRoot))
        BottomNavigation.bind(this, BottomNavigation.TAB_BROWSER)
        bindViews()
        configureWebView()
        bindActions()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })
        if (!loadSharedLink(intent)) {
            lastRequestedPageUrl = BrowserBlocker.HOME_URL
            webView.loadUrl(BrowserBlocker.HOME_URL)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadSharedLink(intent)
    }

    override fun onResume() {
        super.onResume()
        applySecurityFlag()
        BrowserWebViewConfigurator.cookieManager(webView)
            .setAcceptThirdPartyCookies(webView, PrivateBrowserStore.thirdPartyCookiesEnabled(this))
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        hideCustomView()
        SocialMediaDownloader.cancelActiveDownloads()
        runCatching {
            webView.stopLoading()
            if (!BrowserWebViewConfigurator.supportsProfiles()) clearPrivateSession()
            webView.destroy()
        }
        BrowserWebViewConfigurator.resetPrivateProfile()
        downloadExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun bindViews() {
        webView = findViewById(R.id.browserWebView)
        address = findViewById(R.id.browserAddress)
        title = findViewById(R.id.browserTitle)
        subtitle = findViewById(R.id.browserSubtitle)
        progress = findViewById(R.id.browserProgress)
        customContainer = findViewById(R.id.browserFullscreenContainer)
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

    private fun recoverWebViewAfterRendererGone(deadWebView: WebView, restoreUrl: String) {
        if (isFinishing || isDestroyed || deadWebView !== webView) return
        val parent = deadWebView.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(deadWebView).coerceAtLeast(0)
        val params = deadWebView.layoutParams
        parent.removeView(deadWebView)
        runCatching { deadWebView.destroy() }
        webProfileName = BrowserWebViewConfigurator.profileForUrl(restoreUrl)
        if (webProfileName == BrowserWebViewConfigurator.PROFILE_PRIVATE) BrowserWebViewConfigurator.resetPrivateProfile()
        webView = WebView(this).apply {
            id = R.id.browserWebView
            layoutParams = params
        }
        parent.addView(webView, index)
        configureWebView()
        applySecurityFlag()
        webView.loadUrl(restoreUrl)
    }

    private fun configureWebView() {
        val environment = BrowserWebViewConfigurator.configure(
            webView = webView,
            mixedContentCompatibility = PrivateBrowserStore.mixedContentCompatibility(this),
            thirdPartyCookies = PrivateBrowserStore.thirdPartyCookiesEnabled(this),
            profileName = webProfileName
        )
        defaultUserAgent = environment.defaultUserAgent
        applyDesktopMode()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                rememberRequestedPage(url)
                if (BrowserBlocker.shouldOpenOutside(url)) {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    return true
                }
                val targetProfile = BrowserWebViewConfigurator.profileForUrl(url)
                if (BrowserWebViewConfigurator.supportsProfiles() && targetProfile != webProfileName) {
                    loadIsolatedUrl(url)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url?.toString()
                if (BrowserVaultDownloader.isLikelyMediaUrl(url) || BrowserVaultDownloader.isLikelyStreamUrl(url)) {
                    detectedMediaUrls.add(requireNotNull(url))
                }
                return if (PrivateBrowserStore.adBlockEnabled(this@PrivateBrowserActivity) && BrowserBlocker.shouldBlock(url)) {
                    blockedCount.incrementAndGet()
                    emptyBlockedResponse()
                } else null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageLoading = true
                rememberRequestedPage(url.orEmpty(), allowLoginWall = false)
                blockedCount.set(0)
                detectedMediaUrls.clear()
                progress.visibility = View.VISIBLE
                progress.progress = 8
                address.setText(url.orEmpty())
                updateStoryDownload(url.orEmpty())
                subtitle.text = "Carregando…"
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageLoading = false
                address.setText(url.orEmpty())
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

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                pageLoading = false
                progress.visibility = View.GONE
                subtitle.text = "Falha ao carregar • ${error.errorCode}: ${error.description}"
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (!request.isForMainFrame || errorResponse.statusCode < 400) return
                pageLoading = false
                progress.visibility = View.GONE
                subtitle.text = "Falha HTTP ${errorResponse.statusCode}"
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== webView) return true
                pageLoading = false
                progress.visibility = View.GONE
                val restoreUrl = lastRequestedPageUrl.takeIf { it.isNotBlank() } ?: BrowserBlocker.HOME_URL
                subtitle.text = if (detail.didCrash()) "Renderer reiniciado após falha" else "Renderer reiniciado após falta de memória"
                window.decorView.post { recoverWebViewAfterRendererGone(view, restoreUrl) }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress.coerceIn(0, 100)
                progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
            }

            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                title.text = pageTitle?.takeIf { it.isNotBlank() } ?: "Navegador privado"
                updatePageLabels()
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                customContainer.addView(view, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                customContainer.visibility = View.VISIBLE
                WindowCompat.getInsetsController(window, window.decorView)
                    .hide(WindowInsetsCompat.Type.statusBars())
            }

            override fun onHideCustomView() = hideCustomView()
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
        webView.setOnLongClickListener {
            val candidate = webView.hitTestResult?.extra.orEmpty()
            if (candidate.isNotBlank() && BrowserVaultDownloader.isLikelyMediaUrl(candidate)) {
                download(candidate, webView.settings.userAgentString.orEmpty(), "", "")
                true
            } else false
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.browserGo).setOnClickListener { loadAddress() }
        address.setOnEditorActionListener { _, _, _ ->
            loadAddress()
            true
        }
        findViewById<View>(R.id.browserBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<View>(R.id.browserForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.browserRefresh).setOnClickListener { if (pageLoading) webView.stopLoading() else webView.reload() }
        findViewById<View>(R.id.browserHome).setOnClickListener { loadIsolatedUrl(BrowserBlocker.HOME_URL) }
        findViewById<View>(R.id.browserMenu).setOnClickListener { showBrowserMenu() }
        storyDownload.setOnClickListener { downloadCurrentInstagramStory() }
    }

    private fun loadAddress() {
        val normalized = BrowserBlocker.normalizeAddress(address.text?.toString().orEmpty())
        loadIsolatedUrl(normalized)
        address.clearFocus()
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(address.windowToken, 0)
        webView.requestFocus()
    }

    private fun loadSharedLink(intent: Intent): Boolean {
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
            Intent.ACTION_SEND -> BrowserSharedLinkParser.firstHttpUrl(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
            else -> null
        } ?: return false
        if (customView != null) hideCustomView()
        webView.stopLoading()
        address.setText(url)
        loadIsolatedUrl(url)
        return true
    }

    private fun rememberRequestedPage(url: String, allowLoginWall: Boolean = true) {
        if (url.isBlank()) return
        val lower = url.lowercase(java.util.Locale.US)
        if (!allowLoginWall && (lower.contains("/accounts/login") || lower.contains("/login"))) return
        if (lower.startsWith("http://") || lower.startsWith("https://")) lastRequestedPageUrl = url
    }

    private fun showBrowserMenu() {
        val adBlock = PrivateBrowserStore.adBlockEnabled(this)
        val desktop = PrivateBrowserStore.desktopMode(this)
        val cookies = PrivateBrowserStore.thirdPartyCookiesEnabled(this)
        val mixedContent = PrivateBrowserStore.mixedContentCompatibility(this)
        val favorite = PrivateBrowserStore.isFavorite(this, webView.url)
        val favoriteCount = PrivateBrowserStore.favorites(this).size
        OneUiDialog.choices(
            activity = this,
            title = "Navegador privado",
            message = "Bloqueios nesta página: ${blockedCount.get()}\nFavoritos: $favoriteCount\nDownloads: ${PrivateBrowserStore.destinationLabel(PrivateBrowserStore.downloadDestination(this))}\nMídia: ${PrivateBrowserStore.mediaPreferenceSummary(this)}",
            choices = listOf(
                OneUiDialog.Choice(if (adBlock) "Bloqueador ligado" else "Bloqueador desligado", "Remove anúncios, rastreadores e pop-ups comuns."),
                OneUiDialog.Choice(if (favorite) "Remover favorito" else "Adicionar favorito", "Salva este site na lista de favoritos do navegador."),
                OneUiDialog.Choice("Favoritos", if (favoriteCount == 0) "Nenhum favorito salvo." else "Abrir um site salvo."),
                OneUiDialog.Choice(if (cookies) "Cookies completos ligados" else "Cookies completos desligados", "Mantém sessão e permite cookies de terceiros quando o site precisa."),
                OneUiDialog.Choice(if (mixedContent) "Compatibilidade HTTP ligada" else "HTTPS estrito", if (mixedContent) "Permite conteúdo HTTP dentro de páginas HTTPS somente para sites antigos." else "Bloqueia conteúdo HTTP inseguro dentro de páginas HTTPS."),
                OneUiDialog.Choice(if (desktop) "Modo celular" else "Modo desktop", "Alterna o agente do navegador e recarrega a página."),
                OneUiDialog.Choice("Baixar mídia da página", "Procura vídeos, fotos e links diretos que o site disponibilizou."),
                OneUiDialog.Choice("Destino dos downloads", PrivateBrowserStore.destinationLabel(PrivateBrowserStore.downloadDestination(this))),
                OneUiDialog.Choice("Qualidade e resolução de mídia", PrivateBrowserStore.mediaPreferenceSummary(this)),
                OneUiDialog.Choice("Limpar dados do navegador", "Apaga dados temporários desta sessão. Favoritos continuam salvos."),
                OneUiDialog.Choice("Abrir no navegador do celular", "Abre o site atual fora do SteadyVault.")
            )
        ) { option ->
            when (option) {
                0 -> {
                    PrivateBrowserStore.setAdBlockEnabled(this, !adBlock)
                    Toast.makeText(this, if (!adBlock) "Bloqueador ligado" else "Bloqueador desligado", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
                1 -> toggleFavorite()
                2 -> showFavorites()
                3 -> {
                    PrivateBrowserStore.setThirdPartyCookiesEnabled(this, !cookies)
                    BrowserWebViewConfigurator.cookieManager(webView).setAcceptThirdPartyCookies(webView, !cookies)
                    Toast.makeText(this, if (!cookies) "Cookies completos ligados" else "Cookies completos desligados", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
                4 -> {
                    PrivateBrowserStore.setMixedContentCompatibility(this, !mixedContent)
                    webView.settings.mixedContentMode = if (!mixedContent) WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE else WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    Toast.makeText(this, if (!mixedContent) "Compatibilidade HTTP ligada para esta configuração" else "HTTPS estrito ligado", Toast.LENGTH_SHORT).show()
                    webView.reload()
                }
                5 -> {
                    PrivateBrowserStore.setDesktopMode(this, !desktop)
                    applyDesktopMode()
                    webView.reload()
                }
                6 -> scanPageMediaForDownload()
                7 -> chooseDownloadDestination()
                8 -> chooseMediaPreferences()
                9 -> confirmClearBrowserData()
                10 -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webView.url ?: BrowserBlocker.HOME_URL))) }
            }
        }
    }

    private fun toggleFavorite() {
        val url = webView.url.orEmpty()
        val removed = PrivateBrowserStore.isFavorite(this, url) && PrivateBrowserStore.removeFavorite(this, url)
        val saved = if (removed) false else PrivateBrowserStore.addFavorite(this, webView.title ?: title.text?.toString(), url)
        Toast.makeText(
            this,
            when {
                removed -> "Favorito removido"
                saved -> "Favorito salvo"
                else -> "Abra um site antes de salvar favorito"
            },
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showFavorites() {
        val favorites = PrivateBrowserStore.favorites(this)
        if (favorites.isEmpty()) {
            Toast.makeText(this, "Nenhum favorito salvo", Toast.LENGTH_SHORT).show()
            return
        }
        OneUiDialog.choices(
            activity = this,
            title = "Favoritos",
            choices = favorites.map { OneUiDialog.Choice(it.title, it.url) }
        ) { index -> loadIsolatedUrl(favorites[index].url) }
    }


    private fun scanPageMediaForDownload(directOnly: Boolean = false) {
        webView.evaluateJavascript(MEDIA_SCAN_SCRIPT) { raw ->
            val scan = parseMediaScanResult(raw)
            val detected = synchronized(detectedMediaUrls) { detectedMediaUrls.toList() }
            val direct = (scan.urls + detected)
                .map { it.trim() }
                .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
                .filter { BrowserVaultDownloader.isLikelyMediaUrl(it) }
                .distinct()
                .sortedByDescending(BrowserVaultDownloader::isLikelyVideoUrl)
                .take(MAX_MEDIA_CHOICES)
            val pageUrl = currentExtractorPageUrl()
            val embeddedExtractor = scan.extractorUrl.takeIf(SocialMediaDownloader::canHandle).orEmpty()
            val internalStream = scan.hasInternalStream || detected.any(BrowserVaultDownloader::isLikelyStreamUrl)
            val directFallback = { resolvePageMediaForDownload(pageUrl, direct, internalStream, scan.thumbnailUrl) }
            when {
                directOnly -> directFallback()
                InstagramPublicAccess.isInstagramUrl(pageUrl) && direct.any(BrowserVaultDownloader::isLikelyVideoUrl) -> directFallback()
                embeddedExtractor.isNotBlank() -> resolveSocialMediaForDownload(embeddedExtractor, directFallback)
                SocialMediaDownloader.canHandle(pageUrl) -> resolveSocialMediaForDownload(pageUrl, directFallback)
                (scan.hasVideoPlayer || internalStream) && direct.none(BrowserVaultDownloader::isLikelyVideoUrl) ->
                    resolveSocialMediaForDownload(pageUrl, directFallback)
                else -> directFallback()
            }
        }
    }

    private fun currentExtractorPageUrl(): String {
        val current = webView.url.orEmpty()
        return when {
            InstagramPublicAccess.isStoryUrl(current) -> current
            InstagramPublicAccess.isStoryUrl(lastRequestedPageUrl) -> lastRequestedPageUrl
            BrowserMediaExtractor.isExtractorPage(current) -> current
            BrowserMediaExtractor.isExtractorPage(lastRequestedPageUrl) -> lastRequestedPageUrl
            else -> current.ifBlank { lastRequestedPageUrl }
        }
    }

    private fun resolveSocialMediaForDownload(pageUrl: String, onAnalysisFailure: (() -> Unit)? = null) {
        val progressDialog = OneUiDialog.progress(
            activity = this,
            title = "Procurando mídia",
            message = "Analisando formatos disponíveis…",
            cancelable = false
        )
        val cookie = browserCookie(pageUrl)
        downloadExecutor.execute {
            val media = runCatching { SocialMediaDownloader.analyze(this, pageUrl, cookie) }
            runOnUiThread {
                progressDialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                media.onSuccess(::showSocialMediaFormats).onFailure {
                    if (onAnalysisFailure != null) onAnalysisFailure()
                    else OneUiDialog.message(
                        activity = this,
                        title = "Não foi possível analisar o vídeo",
                        message = it.message ?: "O site não disponibilizou formatos de vídeo compatíveis."
                    )
                }
            }
        }
    }

    private fun showSocialMediaFormats(media: SocialMediaDownloader.AnalyzedMedia) {
        val loader = media.thumbnailUrl.takeIf(String::isNotBlank)?.let {
            BrowserMediaThumbnailLoader(
                userAgent = webView.settings.userAgentString.orEmpty(),
                cookie = browserCookie(it),
                referrer = media.sourceUrl
            )
        }
        val dialog = OneUiDialog.choices(
            activity = this,
            title = media.title.take(64),
            message = "Escolha a qualidade do vídeo. A miniatura identifica a mídia antes de baixar.",
            choices = media.formats.map { OneUiDialog.Choice(it.title, it.detail, thumbnailUrl = media.thumbnailUrl) },
            thumbnailBinder = loader?.let { thumbnailLoader -> thumbnailLoader::bind }
        ) { index -> chooseSocialMediaDestination(media, media.formats[index]) }
        dialog.setOnDismissListener { loader?.close() }
    }

    private fun chooseSocialMediaDestination(media: SocialMediaDownloader.AnalyzedMedia, format: SocialMediaDownloader.FormatChoice) {
        val choices = listOf(
            PrivateBrowserStore.DESTINATION_PRIMARY,
            PrivateBrowserStore.DESTINATION_SECONDARY,
            PrivateBrowserStore.DESTINATION_TERTIARY
        )
        val stored = PrivateBrowserStore.downloadDestination(this)
        OneUiDialog.choices(
            activity = this,
            title = "Salvar vídeo",
            message = "${format.title} • ${format.detail}",
            choices = choices.map { OneUiDialog.Choice(PrivateBrowserStore.destinationLabel(it), PrivateBrowserStore.destinationSubtitle(it)) },
            selectedIndex = choices.indexOf(stored).takeIf { it >= 0 } ?: 0,
            confirmLabel = "OK"
        ) { index ->
            val destination = choices[index]
            PrivateBrowserStore.setDownloadDestination(this, destination)
            downloadSocialMedia(media, format, destination)
        }
    }

    private fun downloadSocialMedia(
        media: SocialMediaDownloader.AnalyzedMedia,
        format: SocialMediaDownloader.FormatChoice,
        destination: String
    ) {
        val referrer = webView.url.orEmpty()
        val cancelled = AtomicBoolean(false)
        val progressDialog = OneUiDialog.progress(
            activity = this,
            title = "Baixando para ${PrivateBrowserStore.destinationLabel(destination)}",
            message = "Preparando download…",
            cancelable = false,
            cancelLabel = "Cancelar download",
            onCancel = {
                cancelled.set(true)
                SocialMediaDownloader.cancelActiveDownloads()
            }
        )
        downloadExecutor.execute {
            val result = runCatching {
                SocialMediaDownloader.download(
                    context = this,
                    media = media,
                    format = format,
                    destination = destination,
                    referrer = referrer,
                    cancelled = { cancelled.get() }
                ) { percent, message -> progressDialog.update(percent, message) }
            }
            runOnUiThread {
                progressDialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(this, "Salvo em ${it.destinationLabel}: ${it.file.name}", Toast.LENGTH_LONG).show()
                }.onFailure {
                    if (InstagramPublicAccess.isInstagramUrl(media.sourceUrl)) {
                        Toast.makeText(this, "Tentando o vídeo já carregado no navegador…", Toast.LENGTH_SHORT).show()
                        scanPageMediaForDownload(directOnly = true)
                    } else {
                        Toast.makeText(this, it.message ?: "Falha ao baixar vídeo", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun resolvePageMediaForDownload(
        pageUrl: String,
        direct: List<String>,
        sawInternalStream: Boolean,
        pageThumbnailUrl: String = ""
    ) {
        val progressDialog = OneUiDialog.progress(
            activity = this,
            title = "Procurando mídia",
            message = "Verificando links diretos e metadados públicos…",
            cancelable = false
        )
        val userAgent = webView.settings.userAgentString.orEmpty()
        val cookie = browserCookie(pageUrl)
        val quality = PrivateBrowserStore.mediaQuality(this)
        val resolution = PrivateBrowserStore.mediaResolution(this)
        val referrer = webView.url.orEmpty()
        downloadExecutor.execute {
            val extracted = runCatching {
                BrowserMediaExtractor.resolve(
                    pageUrl = pageUrl,
                    userAgent = userAgent,
                    cookie = cookie,
                    quality = quality,
                    resolution = resolution
                ) { status -> progressDialog.update(-1, status) }
            }.getOrElse { emptyList() }
            val directCandidates = direct.map { url ->
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
            val merged = (extracted + directCandidates)
                .distinctBy { it.url.substringBefore("#") }
                .map { candidate ->
                    if (candidate.thumbnailUrl.isBlank() &&
                        pageThumbnailUrl.isNotBlank() &&
                        (BrowserVaultDownloader.isLikelyVideoUrl(candidate.url) || candidate.mimeType.startsWith("video/", true))
                    ) {
                        candidate.copy(thumbnailUrl = pageThumbnailUrl)
                    } else candidate
                }
                .sortedWith(
                    compareByDescending<BrowserMediaExtractor.Candidate> {
                        BrowserVaultDownloader.isLikelyVideoUrl(it.url) || it.mimeType.startsWith("video/", true)
                    }.thenByDescending { it.thumbnailUrl.isNotBlank() }
                )
            val videoCandidates = merged.filter {
                BrowserVaultDownloader.isLikelyVideoUrl(it.url) || it.mimeType.startsWith("video/", true)
            }
            val candidates = if (videoCandidates.isNotEmpty()) {
                (videoCandidates + merged.filterNot(videoCandidates::contains).take(MAX_IMAGE_ALTERNATIVES))
                    .take(MAX_MEDIA_CHOICES)
            } else {
                merged.take(MAX_MEDIA_CHOICES)
            }
            runOnUiThread {
                progressDialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (candidates.isEmpty()) {
                    val note = if (sawInternalStream) {
                        "O site mostrou streaming interno, mas não expôs um arquivo direto para salvar."
                    } else {
                        "Nenhum arquivo público direto de foto ou vídeo foi encontrado nesta página."
                    }
                    Toast.makeText(this, note, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val loader = candidates.firstOrNull { it.thumbnailUrl.isNotBlank() }?.let {
                    BrowserMediaThumbnailLoader(
                        userAgent = userAgent,
                        cookie = cookie,
                        referrer = pageUrl
                    )
                }
                val dialog = OneUiDialog.choices(
                    activity = this,
                    title = "Mídias encontradas",
                    message = "Vídeos do player aparecem primeiro. Confira a miniatura antes de baixar. Preferência: ${PrivateBrowserStore.mediaPreferenceSummary(this)}",
                    choices = candidates.map {
                        val type = if (BrowserVaultDownloader.isLikelyVideoUrl(it.url) || it.mimeType.startsWith("video/", true)) {
                            "Vídeo"
                        } else {
                            "Imagem"
                        }
                        OneUiDialog.Choice(
                            it.title.take(48).ifBlank { BrowserVaultDownloader.displayNameForUrl(it.url) },
                            "$type • ${it.subtitle.ifBlank { BrowserVaultDownloader.hostForUrl(it.url) }}",
                            thumbnailUrl = it.thumbnailUrl
                        )
                    },
                    thumbnailBinder = loader?.let { thumbnailLoader -> thumbnailLoader::bind }
                ) { index ->
                    val candidate = candidates[index]
                    download(candidate.url, userAgent, "", candidate.mimeType, candidate.referrer.ifBlank { pageUrl })
                }
                dialog.setOnDismissListener { loader?.close() }
            }
        }
    }

    private fun parseMediaScanResult(raw: String?): PageMediaScan {
        val text = raw?.trim().orEmpty()
        if (text.isBlank() || text == "null") return PageMediaScan(emptyList(), "", "", false, false)
        return runCatching {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                return@runCatching PageMediaScan(
                    urls = List(array.length()) { index -> array.optString(index).orEmpty() },
                    thumbnailUrl = "",
                    extractorUrl = "",
                    hasVideoPlayer = false,
                    hasInternalStream = false
                )
            }
            val result = JSONObject(text)
            val urls = result.optJSONArray("urls") ?: JSONArray()
            PageMediaScan(
                urls = List(urls.length()) { index -> urls.optString(index).orEmpty() },
                thumbnailUrl = result.optString("thumbnailUrl"),
                extractorUrl = result.optString("extractorUrl"),
                hasVideoPlayer = result.optBoolean("hasVideoPlayer"),
                hasInternalStream = result.optBoolean("hasInternalStream")
            )
        }.getOrDefault(PageMediaScan(emptyList(), "", "", false, false))
    }

    private fun chooseDownloadDestination() {
        val values = PrivateBrowserStore.destinationValues
        val current = PrivateBrowserStore.downloadDestination(this)
        OneUiDialog.choices(
            activity = this,
            title = "Destino dos downloads",
            message = "Você ainda pode escolher outro cofre em cada download.",
            choices = values.map { OneUiDialog.Choice(PrivateBrowserStore.destinationLabel(it), PrivateBrowserStore.destinationSubtitle(it)) },
            selectedIndex = values.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setDownloadDestination(this, values[index])
            Toast.makeText(this, "Downloads: ${PrivateBrowserStore.destinationLabel(values[index])}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseMediaPreferences() {
        val quality = PrivateBrowserStore.mediaQuality(this)
        val resolution = PrivateBrowserStore.mediaResolution(this)
        OneUiDialog.choices(
            activity = this,
            title = "Qualidade de mídia",
            message = "Preferência usada quando o site oferece versões de mídia. Links diretos continuam baixando o arquivo original.",
            choices = PrivateBrowserStore.qualityValues.map { OneUiDialog.Choice(PrivateBrowserStore.qualityLabel(it)) },
            selectedIndex = PrivateBrowserStore.qualityValues.indexOf(quality)
        ) { qualityIndex ->
            PrivateBrowserStore.setMediaQuality(this, PrivateBrowserStore.qualityValues[qualityIndex])
            chooseMediaResolution(resolution)
        }
    }

    private fun chooseMediaResolution(current: String = PrivateBrowserStore.mediaResolution(this)) {
        OneUiDialog.choices(
            activity = this,
            title = "Resolução preferida",
            message = "Use Original para não reduzir qualidade. As opções dependem do que o site disponibiliza.",
            choices = PrivateBrowserStore.resolutionValues.map { OneUiDialog.Choice(PrivateBrowserStore.resolutionLabel(it)) },
            selectedIndex = PrivateBrowserStore.resolutionValues.indexOf(current)
        ) { index ->
            PrivateBrowserStore.setMediaResolution(this, PrivateBrowserStore.resolutionValues[index])
            Toast.makeText(this, "Mídia: ${PrivateBrowserStore.mediaPreferenceSummary(this)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearBrowserData() {
        OneUiDialog.confirm(
            activity = this,
            title = "Limpar navegador?",
            message = "Cookies, cache e dados temporários desta sessão serão apagados. Favoritos e mídias dos cofres não são afetados.",
            positiveLabel = "Limpar",
            destructive = true
        ) {
            BrowserWebViewConfigurator.clearPrivateData(webView) {
                if (!isFinishing && !isDestroyed) Toast.makeText(this, "Dados do navegador limpos", Toast.LENGTH_SHORT).show()
            }
            lastRequestedPageUrl = BrowserBlocker.HOME_URL
            webView.loadUrl(BrowserBlocker.HOME_URL)
        }
    }

    private fun applyDesktopMode() {
        val settings = webView.settings
        settings.userAgentString = if (PrivateBrowserStore.desktopMode(this)) BrowserWebViewConfigurator.desktopUserAgent(defaultUserAgent) else defaultUserAgent
    }
    private fun emptyBlockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun updatePageLabels() {
        val host = runCatching { Uri.parse(webView.url).host }.getOrNull().orEmpty()
        val blocked = if (PrivateBrowserStore.adBlockEnabled(this)) " • ${blockedCount.get()} bloqueado(s)" else " • bloqueador off"
        subtitle.text = host.ifBlank { "Navegação privada" } + blocked
    }

    private fun download(url: String, userAgent: String, contentDisposition: String, mimeType: String, referrer: String = "") {
        if (url.startsWith("blob:", true) || url.startsWith("data:", true)) {
            Toast.makeText(this, "Esse site usa mídia interna/stream. Use o botão de download do próprio site quando aparecer.", Toast.LENGTH_LONG).show()
            return
        }
        if (!BrowserVaultDownloader.isLikelyMediaUrl(url, mimeType) && BrowserMediaExtractor.isExtractorPage(url)) {
            if (InstagramPublicAccess.isInstagramUrl(url)) scanPageMediaForDownload()
            else if (SocialMediaDownloader.canHandle(url)) resolveSocialMediaForDownload(url)
            else resolvePageMediaForDownload(url, emptyList(), sawInternalStream = false)
            return
        }
        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType).ifBlank { "download" }
        val storedDestination = PrivateBrowserStore.downloadDestination(this)
        val choices = listOf(
            PrivateBrowserStore.DESTINATION_PRIMARY,
            PrivateBrowserStore.DESTINATION_SECONDARY,
            PrivateBrowserStore.DESTINATION_TERTIARY,
            PrivateBrowserStore.DESTINATION_DOWNLOADS
        )
        val selected = choices.indexOf(storedDestination).takeIf { it >= 0 } ?: -1
        OneUiDialog.choices(
            activity = this,
            title = "Baixar mídia",
            message = "$fileName\nPreferência: ${PrivateBrowserStore.mediaPreferenceSummary(this)}",
            choices = choices.map { OneUiDialog.Choice(PrivateBrowserStore.destinationLabel(it), PrivateBrowserStore.destinationSubtitle(it)) },
            selectedIndex = selected
        ) { index ->
            val destination = choices[index]
            PrivateBrowserStore.setDownloadDestination(this, destination)
            if (destination == PrivateBrowserStore.DESTINATION_DOWNLOADS) {
                downloadToAndroidDownloads(url, userAgent, contentDisposition, mimeType, fileName, referrer)
            } else {
                downloadToVault(url, userAgent, contentDisposition, mimeType, destination, referrer)
            }
        }
    }

    private fun downloadToAndroidDownloads(url: String, userAgent: String, contentDisposition: String, mimeType: String, fileName: String, referrer: String) {
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(fileName)
                .setDescription("Download pelo SteadyVault")
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .addRequestHeader("Cookie", browserCookie(url))
                .addRequestHeader("Referer", referrer.ifBlank { webView.url.orEmpty() })
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val downloadId = getSystemService(DownloadManager::class.java).enqueue(request)
            BrowserDownloadRegistry.add(this, downloadId, fileName)
            Toast.makeText(this, "Download iniciado: $fileName", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, it.message ?: "Falha ao iniciar download", Toast.LENGTH_LONG).show()
        }
    }

    private fun downloadToVault(url: String, userAgent: String, contentDisposition: String, mimeType: String, destination: String, referrer: String) {
        val cancelled = AtomicBoolean(false)
        val progressDialog = OneUiDialog.progress(
            activity = this,
            title = "Baixando para ${PrivateBrowserStore.destinationLabel(destination)}",
            message = "Preparando download…",
            cancelable = false,
            cancelLabel = "Cancelar download",
            onCancel = { cancelled.set(true) }
        )
        val quality = PrivateBrowserStore.mediaQuality(this)
        val resolution = PrivateBrowserStore.mediaResolution(this)
        val cookie = browserCookie(url)
        val effectiveReferrer = referrer.ifBlank { webView.url.orEmpty() }
        downloadExecutor.execute {
            val result = runCatching {
                BrowserVaultDownloader.download(
                    context = this,
                    url = url,
                    userAgent = userAgent,
                    cookie = cookie,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    destination = destination,
                    referrer = effectiveReferrer,
                    quality = quality,
                    resolution = resolution,
                    cancelled = { cancelled.get() }
                ) { percent, message -> progressDialog.update(percent, message) }
            }
            runOnUiThread {
                progressDialog.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(this, "Salvo em ${it.destinationLabel}: ${it.file.name}", Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, it.message ?: "Falha ao baixar para o cofre", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearPrivateSession() {
        runCatching { BrowserWebViewConfigurator.clearPrivateData(webView) }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        customContainer.removeView(view)
        customContainer.visibility = View.GONE
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.statusBars())
    }

    private fun applySecurityFlag() {
        if (CaptureSettings.snapshot(this).secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    companion object {
        private const val MAX_MEDIA_CHOICES = 40
        private const val MAX_IMAGE_ALTERNATIVES = 8
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
            (function() {
                const urls = new Set();
                const thumbnails = [];
                let hasInternalStream = false;
                function add(value) {
                    if (!value) return;
                    const raw = String(value);
                    if (/^(blob:|data:)/i.test(raw)) hasInternalStream = true;
                    try { urls.add(new URL(raw, location.href).href); } catch (e) { urls.add(raw); }
                }
                function addThumbnail(value) {
                    if (!value) return;
                    try {
                        const normalized = new URL(value, location.href).href;
                        if (/^https?:/i.test(normalized) && thumbnails.indexOf(normalized) < 0) thumbnails.push(normalized);
                    } catch (e) {}
                }
                document.querySelectorAll('video,audio').forEach(function(node) {
                    add(node.currentSrc);
                    add(node.src);
                    addThumbnail(node.poster);
                    node.querySelectorAll('source').forEach(function(source) { add(source.src); });
                });
                document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]').forEach(function(node) {
                    add(node.content);
                });
                document.querySelectorAll('meta[property="og:image"],meta[property="og:image:secure_url"],meta[name="twitter:image"],meta[name="twitter:image:src"]').forEach(function(node) {
                    addThumbnail(node.content);
                });
                document.querySelectorAll('a[href]').forEach(function(node) {
                    try {
                        const url = new URL(node.href, location.href);
                        if (/\.(mp4|m4v|mov|webm|mkv|3gp|jpg|jpeg|png|webp|gif|heic|heif)$/i.test(url.pathname)) add(url.href);
                    } catch (e) {}
                });
                let extractorUrl = '';
                document.querySelectorAll('iframe[src]').forEach(function(frame) {
                    if (extractorUrl) return;
                    try {
                        const candidate = new URL(frame.src, location.href).href;
                        if (/(youtube\.com|youtu\.be|instagram\.com|facebook\.com|fb\.watch|tiktok\.com|twitter\.com|x\.com|vimeo\.com)/i.test(candidate)) {
                            extractorUrl = candidate;
                        }
                    } catch (e) {}
                });
                const hasVideoPlayer = !!document.querySelector('video,video source,meta[property^="og:video"],meta[name="twitter:player:stream"],iframe[src*="youtube"],iframe[src*="vimeo"]');
                return {
                    urls: Array.from(urls).slice(0, 160),
                    thumbnailUrl: thumbnails.length ? thumbnails[0] : '',
                    extractorUrl: extractorUrl,
                    hasVideoPlayer: hasVideoPlayer,
                    hasInternalStream: hasInternalStream
                };
            })();
        """.trimIndent()
    }

}
