package com.arktube.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

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
 *    letterboxed "fit" framing -- see ZOOM_TO_FILL_JS below. Done via
 *    a CSS transform on the <video> paint layer only, specifically
 *    *not* by resizing its layout box: an earlier version forced the
 *    box itself to fill the screen, which desynced YouTube's own
 *    click-hit-testing math (computed against the box's real,
 *    unforced size) and upscaled the decoded frame well past its
 *    native resolution, which is what was showing up as "blurry and
 *    nothing's clickable."
 *  - keeps the status/nav bar color in sync with whichever theme
 *    YouTube itself is rendering (its own light/dark toggle, not the
 *    phone's system theme) -- see THEME_SYNC_JS and ThemeBridge below
 *  - hides YouTube's "open app" nag button/banner (nudging you at the
 *    native YouTube app instead) since this app already *is* that
 *    experience wrapped natively -- see HIDE_OPEN_APP_JS below
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

        // Lets fullscreen video draw under the notch/camera cutout
        // instead of YouTube's custom view being letterboxed around
        // it. Must be set on the window's LayoutParams directly (not
        // just the insets controller) or the cutout area stays
        // reserved regardless of what onShowCustomView does later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }

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

        // Lets THEME_SYNC_JS hand the detected page background back
        // to native code (JS running in the WebView has no other way
        // to reach Kotlin). "ArkTubeTheme" here is exactly the global
        // name THEME_SYNC_JS calls as window.ArkTubeTheme.
        webView.addJavascriptInterface(ThemeBridge(), "ArkTubeTheme")

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
                view.evaluateJavascript(THEME_SYNC_JS, null)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
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
                enterImmersiveFullscreen()
                // Custom view is the real fullscreen surface, not
                // the WebView -- FLAG_KEEP_SCREEN_ON has to be on
                // *this* window regardless of which view is
                // currently attached as content, so it applies
                // either way, but set it explicitly here so it's
                // guaranteed on for the duration playback is in this
                // native fullscreen view.
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // Safety net: ZOOM_TO_FILL_JS should already be
                // installed from onPageFinished, but this covers the
                // (unlikely) case fullscreen was reached before that
                // fired. Harmless to call again either way -- it's
                // guarded against installing its listeners twice.
                webView.evaluateJavascript(ZOOM_TO_FILL_JS, null)
            }

            override fun onHideCustomView() {
                setContentView(webView)
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                exitImmersiveFullscreen()
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

    // Receives theme reports from THEME_SYNC_JS. A plain
    // @JavascriptInterface-annotated class rather than a lambda
    // because that annotation is what the WebView bridge requires to
    // expose methods to page JS at all.
    private inner class ThemeBridge {
        @JavascriptInterface
        fun onThemeChanged(isDark: Boolean, cssBackground: String?) {
            // Called on the WebView's own JS thread, not the UI
            // thread -- window.statusBarColor etc. need the latter.
            runOnUiThread { applyStatusBarTheme(isDark, cssBackground) }
        }
    }

    /**
     * Paints the status/nav bars to match the color THEME_SYNC_JS
     * found YouTube actually rendering (falling back to a plain
     * light/dark swatch if that color couldn't be read, e.g. it came
     * back transparent). Also flips the bar icons' own light/dark
     * appearance so they stay legible against whichever it picked.
     */
    private fun applyStatusBarTheme(isDark: Boolean, cssBackground: String?) {
        val barColor = parseCssColor(cssBackground)
            ?: if (isDark) FALLBACK_DARK_COLOR else FALLBACK_LIGHT_COLOR

        window.statusBarColor = barColor
        window.navigationBarColor = barColor

        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDark
        insetsController.isAppearanceLightNavigationBars = !isDark
    }

    /**
     * Drops the status bar, nav bar, and (via the cutout mode set in
     * onCreate) the notch/cutout inset -- entered only while
     * YouTube's custom fullscreen view is showing, so browsing the
     * rest of the site keeps normal system bars. `hide()` alone
     * would still reserve the cutout's inset as blank space even
     * with the bars gone; layoutInDisplayCutoutMode is what actually
     * lets content draw underneath it. BEHAVIOR_SHOW_TRANSIENT_...
     * means a swipe from the edge peeks the bars back temporarily
     * (for the user to exit, adjust volume, etc.) without permanently
     * exiting fullscreen the way SHOW_BARS_BY_TOUCH would.
     */
    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** Restores normal system bars when leaving fullscreen video. */
    private fun exitImmersiveFullscreen() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    /**
     * Parses a CSS color() computed-style string (always
     * "rgb(r, g, b)" or "rgba(r, g, b, a)" from getComputedStyle,
     * regardless of how the color was originally authored) into an
     * Android color int. Returns null for anything unparseable or
     * fully transparent, since a transparent background isn't a real
     * color to sync the status bar to.
     */
    private fun parseCssColor(css: String?): Int? {
        if (css == null) return null
        val components = Regex("[\\d.]+").findAll(css)
            .mapNotNull { it.value.toFloatOrNull() }
            .toList()
        if (components.size < 3) return null
        val alpha = if (components.size > 3) components[3] else 1f
        if (alpha <= 0f) return null
        val r = components[0].toInt().coerceIn(0, 255)
        val g = components[1].toInt().coerceIn(0, 255)
        val b = components[2].toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    companion object {
        private const val SITE_URL = "https://m.youtube.com"

        // Crops fullscreen video to fill the screen instead of
        // YouTube's default letterboxed "fit". Scoped to *only* the
        // <video> element inside document.fullscreenElement, and
        // *only* a CSS transform -- never its layout box (width/
        // height/object-fit), since YouTube's own controls read the
        // video's real box size to position/hit-test themselves; a
        // transform changes what's painted (and, correctly, what
        // taps land on) without touching that box at all, so the
        // controls stay exactly where YouTube put them.
        //
        // Re-evaluates on fullscreenchange, on resize (covers
        // rotation), and a couple of short delayed retries after
        // entering fullscreen, since YouTube sometimes resizes its
        // player again shortly after the fullscreen transition
        // itself (e.g. once it settles on the actual stream
        // dimensions). Guarded by a flag on `window` so re-injecting
        // this on every onPageFinished doesn't register duplicate
        // listeners within the same page's JS context.
        private const val ZOOM_TO_FILL_JS = """
            (function() {
                if (window.__arktubeZoomToFillInstalled) { return; }
                window.__arktubeZoomToFillInstalled = true;

                var zoomedVideo = null;

                function fullscreenVideo() {
                    var el = document.fullscreenElement || document.webkitFullscreenElement;
                    if (!el) { return null; }
                    return el.tagName === 'VIDEO' ? el : el.querySelector('video');
                }

                function clearZoom(video) {
                    if (!video) { return; }
                    video.style.transform = '';
                    video.style.transformOrigin = '';
                }

                function applyZoom() {
                    var video = fullscreenVideo();
                    if (zoomedVideo && zoomedVideo !== video) {
                        clearZoom(zoomedVideo);
                    }
                    zoomedVideo = video;
                    if (!video) { return; }

                    var rect = video.getBoundingClientRect();
                    if (!rect.width || !rect.height) { return; }

                    var scale = Math.max(
                        window.innerWidth / rect.width,
                        window.innerHeight / rect.height
                    );

                    if (!isFinite(scale) || scale <= 1.01) {
                        clearZoom(video);
                        return;
                    }

                    video.style.transformOrigin = 'center center';
                    video.style.transform = 'scale(' + scale.toFixed(4) + ')';
                }

                var pending = false;
                function scheduleApplyZoom() {
                    if (pending) { return; }
                    pending = true;
                    requestAnimationFrame(function() {
                        pending = false;
                        applyZoom();
                    });
                }

                document.addEventListener('fullscreenchange', scheduleApplyZoom);
                document.addEventListener('webkitfullscreenchange', scheduleApplyZoom);
                window.addEventListener('resize', scheduleApplyZoom);
                document.addEventListener('fullscreenchange', function() {
                    setTimeout(scheduleApplyZoom, 300);
                    setTimeout(scheduleApplyZoom, 1000);
                });
            })();
        """

        // Used only when THEME_SYNC_JS reports a theme but couldn't
        // read a real (non-transparent) background color to match --
        // plain swatches close to YouTube's own light/dark palette.
        private const val FALLBACK_DARK_COLOR = 0xFF0F0F0F.toInt()
        private const val FALLBACK_LIGHT_COLOR = 0xFFFFFFFF.toInt()

        // Reports YouTube's actual rendered page background back to
        // ThemeBridge, so the status/nav bar can match *YouTube's*
        // light/dark toggle specifically -- not the phone's system
        // theme, which YouTube's own setting can (and often does)
        // disagree with. Reads the live background color rather than
        // any specific class/attribute YouTube uses to mark dark
        // mode, since that markup is an implementation detail that
        // could change; the rendered color can't.
        //
        // A MutationObserver catches most in-page theme toggles, but
        // YouTube doesn't always touch an attribute the observer is
        // watching when it flips themes, so a slow poll interval
        // backs it up. Guarded by a flag on `window` so re-injecting
        // this on every onPageFinished doesn't stack up duplicate
        // observers/intervals within the same page's JS context.
        private const val THEME_SYNC_JS = """
            (function() {
                if (window.__arktubeThemeSyncInstalled) { return; }
                window.__arktubeThemeSyncInstalled = true;

                function readBackground(el) {
                    if (!el) { return null; }
                    return window.getComputedStyle(el).backgroundColor;
                }

                // getComputedStyle always normalizes to
                // "rgb(r, g, b)" / "rgba(r, g, b, a)", regardless of
                // how the color was originally authored (hex, named,
                // CSS var, etc).
                function isDark(rgbaString) {
                    if (!rgbaString) { return null; }
                    var nums = rgbaString.match(/[\d.]+/g);
                    if (!nums || nums.length < 3) { return null; }
                    var alpha = nums.length > 3 ? parseFloat(nums[3]) : 1;
                    if (alpha === 0) { return null; }
                    var r = parseFloat(nums[0]);
                    var g = parseFloat(nums[1]);
                    var b = parseFloat(nums[2]);
                    var luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    return luminance < 128;
                }

                function report() {
                    var bg = readBackground(document.body);
                    var dark = isDark(bg);
                    if (dark === null) {
                        // <body> can be transparent while the real
                        // background lives on <html> instead.
                        bg = readBackground(document.documentElement);
                        dark = isDark(bg);
                    }
                    if (dark !== null && window.ArkTubeTheme) {
                        window.ArkTubeTheme.onThemeChanged(dark, bg);
                    }
                }

                report();

                var observer = new MutationObserver(report);
                observer.observe(document.documentElement, { attributes: true });
                if (document.body) {
                    observer.observe(document.body, { attributes: true });
                }

                setInterval(report, 2000);
            })();
        """

        // Hides YouTube's own "open app"/install-the-app nag, in the
        // topbar and as promo banners elsewhere on the page --
        // there's no reason to prompt someone to switch to the
        // native YouTube app when this *is* that experience, just
        // wrapped natively.
        //
        // ".mobile-topbar-header-open-app-button" and
        // "ytm-mealbar-promo-renderer" are the two concrete elements
        // this is known to show up as, but since neither is
        // documented anywhere and YouTube's markup can change
        // without notice, this also falls back to a text-based scan
        // restricted to header/topbar-ish containers -- broad enough
        // to survive a class rename, narrow enough not to risk
        // hiding unrelated header buttons that just happen to share
        // a class prefix. Polled on an interval rather than a
        // MutationObserver, since observing the whole document for
        // this would fire constantly on a page as dynamic as
        // YouTube's (video progress, live chat, etc.).
        private const val HIDE_OPEN_APP_JS = """
            (function() {
                if (window.__arktubeHideOpenAppInstalled) { return; }
                window.__arktubeHideOpenAppInstalled = true;

                var STYLE_ID = 'arktube-hide-open-app';
                if (!document.getElementById(STYLE_ID)) {
                    var style = document.createElement('style');
                    style.id = STYLE_ID;
                    style.textContent =
                        '.mobile-topbar-header-open-app-button, ' +
                        'ytm-mealbar-promo-renderer { ' +
                        '  display: none !important; ' +
                        '}';
                    document.head.appendChild(style);
                }

                function hideByText() {
                    var scopes = document.querySelectorAll(
                        'ytm-mobile-topbar-renderer, header, [class*="topbar" i]'
                    );
                    scopes.forEach(function(scope) {
                        var candidates = scope.querySelectorAll('button, a, ytd-button-renderer, tp-yt-paper-button');
                        candidates.forEach(function(el) {
                            var text = (el.textContent || '').trim().toLowerCase();
                            if (text === 'open app' || text === 'open in app' || text === 'get the app') {
                                el.style.display = 'none';
                            }
                        });
                    });
                }

                hideByText();
                setInterval(hideByText, 1500);
            })();
        """
    }
}
