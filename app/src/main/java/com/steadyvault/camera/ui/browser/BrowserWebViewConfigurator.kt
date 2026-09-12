package com.steadyvault.camera.ui.browser

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
