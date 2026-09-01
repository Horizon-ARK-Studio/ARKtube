package com.arktube.app.webview

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.arktube.app.logging.ArkLogger
import com.arktube.app.webview.bridge.FullscreenVideoSizeListener
import com.arktube.app.webview.bridge.MediaPlaybackBridge
import com.arktube.app.webview.bridge.MediaPlaybackListener
import com.arktube.app.webview.bridge.OrientationBridge
import com.arktube.app.webview.bridge.ThemeBridge
import com.arktube.app.webview.bridge.ThemeChangeListener

/**
 * GoF Factory: the single place that knows how to assemble a
 * fully-configured ARKtube WebView (settings, user agent, the three
 * JS bridges, and script injection on page load). Callers hand in
 * listeners and native callbacks; they never touch WebSettings
 * directly.
 */
object ArkTubeWebViewFactory {

    private const val COMPONENT = "ArkTubeWebViewFactory"
    const val SITE_URL = "https://m.youtube.com"

    fun create(
        context: Context,
        themeListener: ThemeChangeListener,
        orientationListener: FullscreenVideoSizeListener,
        mediaPlaybackListener: MediaPlaybackListener,
        webChromeClient: WebChromeClient
    ): WebView = ArkLogger.track(COMPONENT, "create") {
        val webView = WebView(context)
        try {
            configureSettings(webView)
            attachBridges(webView, themeListener, orientationListener, mediaPlaybackListener)
            webView.webViewClient = buildWebViewClient()
            webView.webChromeClient = webChromeClient
        } catch (t: Throwable) {
            ArkLogger.e(COMPONENT, "Failed while configuring WebView", t)
            throw t
        } finally {
            ArkLogger.d(COMPONENT, "WebView configuration pass finished")
        }
        webView
    }

    private fun configureSettings(webView: WebView) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // See the original MainActivity class doc (kept in git history)
        // for the full explanation: without this, WebView ignores the
        // page's own viewport meta tag and m.youtube.com renders its
        // desktop-style sidebar layout instead of the phone layout.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Identify as a mobile browser so YouTube serves m.youtube.com's
        // actual mobile layout rather than a desktop layout squeezed
        // into a phone-sized WebView.
        webView.settings.userAgentString = webView.settings.userAgentString?.replace("; wv", "")
    }

    private fun attachBridges(
        webView: WebView,
        themeListener: ThemeChangeListener,
        orientationListener: FullscreenVideoSizeListener,
        mediaPlaybackListener: MediaPlaybackListener
    ) {
        webView.addJavascriptInterface(ThemeBridge(themeListener), "ArkTubeTheme")
        webView.addJavascriptInterface(OrientationBridge(orientationListener), "ArkTubeOrientation")
        webView.addJavascriptInterface(MediaPlaybackBridge(mediaPlaybackListener), "ArkTubeMediaPlayback")
    }

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            ArkLogger.track(COMPONENT, "onPageFinished:$url") {
                view.evaluateJavascript(ArkTubeScripts.VIDEO_SIZE_REPORT_JS, null)
                view.evaluateJavascript(ArkTubeScripts.THEME_SYNC_JS, null)
                view.evaluateJavascript(ArkTubeScripts.HIDE_OPEN_APP_JS, null)
                view.evaluateJavascript(ArkTubeScripts.MEDIA_SESSION_JS, null)
            }
        }
    }
}
