package com.arktube.app

import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Stage 0 scaffold for the ARKtube Android edition.
 *
 * This mirrors the desktop app's model exactly (see
 * ../../../docs/PROBLEM-STATEMENT.md at the repo root): don't
 * redesign YouTube, don't bundle a copy of it -- just point a WebView
 * at the real, live site and let YouTube be YouTube. There is no
 * `assets/` bundle and no WebViewAssetLoader here (unlike a
 * static-site shell); `loadUrl` goes straight at m.youtube.com over
 * plain HTTPS, which is also why AndroidManifest.xml declares the
 * INTERNET permission this build actually needs.
 *
 * Stage 0 scope, deliberately narrow (matching the desktop README's
 * "the project will stay deliberately small until the underlying
 * approach is proven"):
 *  - loads YouTube
 *  - keeps JS/DOM storage/session cookies working (login persistence)
 *  - lets in-app back navigation walk the WebView's own history first
 *  - allows fullscreen video (WebChromeClient's
 *    on/onHideCustomView) since YouTube's HTML5 player needs it for
 *    the fullscreen button to do anything
 *  - zooms fullscreen video to fill the screen (cropping any
 *    letterbox/pillarbox bars) instead of leaving YouTube's default
 *    letterboxed "fit" framing -- see ZOOM_TO_FILL_JS below
 *
 * Explicitly out of scope for Stage 0 (future stages, see the
 * repo-root roadmap): a persistent nav shell/sidebar, download
 * interception, PiP, media-session/notification controls,
 * chromecast, ad-blocking, or any custom UI layered over the page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var customView: android.view.View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() -- swaps
        // Theme.ArkTubeApp.Starting (set on this activity in the
        // manifest) for its postSplashScreenTheme (Theme.ArkTubeApp)
        // once the splash screen is dismissed.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        // YouTube's HTML5 player calls play() without a direct user
        // tap in some flows (autoplay-next, restoring playback
        // position); WebView blocks that by default.
        webView.settings.mediaPlaybackRequiresUserGesture = false
        // Identify as a mobile browser so YouTube serves its mobile
        // web UI (m.youtube.com's actual layout) rather than a
        // desktop layout squeezed into a phone-sized WebView.
        webView.settings.userAgentString = webView.settings.userAgentString
            ?.replace("; wv", "")

        webView.webViewClient = object : WebViewClient() {
            // YouTube's fullscreen button doesn't hand the WebView a
            // bare <video>; it puts the player into the page's own
            // Fullscreen API and WebChromeClient.onShowCustomView
            // just mirrors that DOM state natively. So the "fit"
            // vs. "fill" framing is still ultimately CSS, and we can
            // reapply it here on every page load so it's already in
            // place by the time the fullscreen button is tapped.
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(ZOOM_TO_FILL_JS, null)
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            // Fullscreen video support: YouTube's player swaps in a
            // custom fullscreen view via these callbacks. Without
            // handling them, the in-page fullscreen button is a
            // dead click.
            override fun onShowCustomView(
                view: android.view.View,
                callback: CustomViewCallback
            ) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                setContentView(view)
                // Re-inject on entering fullscreen too: the
                // stylesheet from onPageFinished should already be
                // there, but YouTube's SPA navigation can swap in a
                // fresh player instance between page load and the
                // fullscreen tap, so make sure it's applied now.
                webView.evaluateJavascript(ZOOM_TO_FILL_JS, null)
            }

            override fun onHideCustomView() {
                setContentView(webView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
            }
        }

        webView.loadUrl(SITE_URL)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            customView != null -> webView.webChromeClient?.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    companion object {
        private const val SITE_URL = "https://m.youtube.com"

        // Forces fullscreen video to zoom-to-fill (crop to the
        // screen edges) instead of YouTube's default zoom-to-fit
        // (letterboxed, with black bars on mismatched aspect
        // ratios). Injected as a <style> tag rather than one-off
        // inline styles so it survives YouTube's own player
        // re-renders, which reset inline style attributes but leave
        // stylesheet rules alone. Idempotent: re-running it just
        // reuses the existing <style> tag instead of stacking dupes.
        private const val ZOOM_TO_FILL_JS = """
            (function() {
                var STYLE_ID = 'arktube-zoom-to-fill';
                if (document.getElementById(STYLE_ID)) { return; }
                var style = document.createElement('style');
                style.id = STYLE_ID;
                style.textContent =
                    ':fullscreen video, ' +
                    ':-webkit-full-screen video, ' +
                    '.html5-video-player.ytp-fullscreen video, ' +
                    '.html5-video-player.ytp-fullscreen .html5-main-video, ' +
                    '.html5-video-player.ytp-fullscreen .video-stream { ' +
                    '  object-fit: cover !important; ' +
                    '  width: 100% !important; ' +
                    '  height: 100% !important; ' +
                    '}';
                document.head.appendChild(style);
            })();
        """
    }
}
