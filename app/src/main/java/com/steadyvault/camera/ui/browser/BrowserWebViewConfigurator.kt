package com.steadyvault.camera.ui.browser

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

object BrowserWebViewConfigurator {

    data class Environment(
        val defaultUserAgent: String,
        val webViewPackage: String,
        val webViewVersion: String
    )

    fun configure(webView: WebView, mixedContentCompatibility: Boolean, thirdPartyCookies: Boolean): Environment {
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

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            WebSettingsCompat.setWebAuthenticationSupport(settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
        }

        WebView.setWebContentsDebuggingEnabled(false)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookies)

        val packageInfo = WebViewCompat.getCurrentWebViewPackage(webView.context.applicationContext)

        return Environment(
            defaultUserAgent = settings.userAgentString.orEmpty(),
            webViewPackage = packageInfo?.packageName.orEmpty(),
            webViewVersion = packageInfo?.versionName.orEmpty()
        )
    }

    fun currentUserAgent(context: android.content.Context): String =
        WebSettings.getDefaultUserAgent(context.applicationContext).orEmpty()

    fun desktopUserAgent(defaultUserAgent: String): String = defaultUserAgent
        .replace("; wv", "", ignoreCase = true)
        .replace(" Mobile ", " ", ignoreCase = true)
        .replace(" Mobile", "", ignoreCase = true)
        .replace(Regex("\\s{2,}"), " ")
        .trim()

    fun clearPrivateData(webView: WebView, onDone: (() -> Unit)? = null) {
        webView.clearHistory()
        webView.clearCache(true)

        val finish = Runnable {
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                onDone?.invoke()
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            val uiExecutor = Executor { command -> webView.post(command) }
            WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), uiExecutor, finish)
        } else {
            WebStorage.getInstance().deleteAllData()
            finish.run()
        }
    }
}