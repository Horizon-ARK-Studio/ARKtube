package com.arktube.app

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
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
 *    nothing's clickable." The scale itself is computed from the
 *    video's own intrinsic pixel size vs. its container, not the
 *    video element's rendered box -- YouTube's fullscreen CSS
 *    already stretches that box to fill the screen (object-fit:
 *    contain paints the real letterboxed picture *inside* it), so
 *    measuring the box directly is comparing screen size to screen
 *    size and never actually crops anything.
 *  - goes truly edge-to-edge for fullscreen video: hides the status
 *    bar, nav bar (gesture pill or 3-button), and draws under the
 *    notch/camera cutout -- see enterImmersiveFullscreen()/
 *    exitImmersiveFullscreen() and the layoutInDisplayCutoutMode
 *    setup in onCreate()
 *  - keeps the screen from sleeping/locking while fullscreen video is
 *    on screen (FLAG_KEEP_SCREEN_ON, toggled in on/onHideCustomView)
 *  - rotates fullscreen video to match the video's own orientation --
 *    landscape upload gets a landscape-locked fullscreen, portrait/
 *    Shorts gets portrait -- overriding the phone's system
 *    auto-rotate lock the way the YouTube app does, and restoring
 *    whatever orientation preceded fullscreen once it ends -- see
 *    OrientationBridge/applyFullscreenOrientation() and the
 *    ArkTubeOrientation calls inside ZOOM_TO_FILL_JS
 *  - keeps the status/nav bar color in sync with whichever theme
 *    YouTube itself is rendering (its own light/dark toggle, not the
 *    phone's system theme) -- see THEME_SYNC_JS and ThemeBridge below
 *  - hides YouTube's "open app" nag button/banner (nudging you at the
 *    native YouTube app instead) since this app already *is* that
 *    experience wrapped natively -- see HIDE_OPEN_APP_JS below
 *  - offers a manual "stretch to fill" toggle over fullscreen video,
 *    since the automatic crop-to-fill in ZOOM_TO_FILL_JS only
 *    engages itself when it measures real letterbox/pillarbox bars
 *    to crop -- there was previously no way for a user to *ask* for
 *    it, only for the code to decide on its own -- see
 *    buildStretchToggleButton()/toggleForceFill() and
 *    __arktubeSetForceFill in ZOOM_TO_FILL_JS
 *  - reasserts immersive mode on every window-focus regain while
 *    fullscreen video is showing (see onWindowFocusChanged), since
 *    Android silently redraws the system bars on any focus churn --
 *    including the brief refocus YouTube's own in-page settings/
 *    quality menu causes when it opens -- and a stray transient bar
 *    sitting in front of the page is what was swallowing the tap
 *    meant for that menu (or its gear icon) instead of passing it
 *    through
 *  - pushes the fullscreen container's real, native-measured size to
 *    the page on every layout pass (rotation, insets settling) --
 *    see reportViewportToPage()/__arktubeSetViewport in
 *    ZOOM_TO_FILL_JS -- so the crop math has an immediately-current
 *    number to work from instead of racing window.innerWidth/
 *    getBoundingClientRect(), which can still reflect the outgoing
 *    orientation for a frame or two right after a rotation
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

    // Set once in onCreate as the Activity's one and only
    // setContentView() target, and never swapped out again -- see
    // onShowCustomView()/onHideCustomView() for why. Holds webView
    // for the app's entire lifetime; the fullscreen container is
    // added/removed as a second child on top of it.
    private lateinit var rootLayout: FrameLayout

    // Wraps the raw customView Chromium hands us in onShowCustomView
    // together with the stretch-to-fill toggle button, so the button
    // rides along as a native overlay on top of the video for the
    // duration of fullscreen without touching the WebView's own DOM.
    private var fullscreenContainer: FrameLayout? = null
    private var stretchToggleButton: Button? = null

    // Persisted so the choice survives fullscreen exit/re-entry and
    // app restarts, not just the current fullscreen session.
    private lateinit var prefs: android.content.SharedPreferences
    private var forceFillEnabled = false

    // Orientation to restore once fullscreen video ends -- captured
    // fresh each time fullscreen is entered (not just once in
    // onCreate) since it should snap back to whatever the user was
    // actually in immediately before, not a fixed default.
    private var preFullscreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() -- swaps
        // Theme.ArkTubeApp.Starting (set on this activity in the
        // manifest) for its postSplashScreenTheme (Theme.ArkTubeApp)
        // once the splash screen is dismissed.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        forceFillEnabled = prefs.getBoolean(PREF_FORCE_FILL, false)

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

        // webView is added to rootLayout here and never removed or
        // detached again for the rest of the Activity's life --
        // notably, *not even while fullscreen video is showing*. See
        // onShowCustomView()/onHideCustomView() below for why that
        // matters: Android's WebView ties the page's Page Visibility
        // API (document.hidden/'visibilitychange') to the WebView's
        // own window-attachment state, not just whether the app is
        // foregrounded. A previous version of this code called
        // setContentView(customView) to show fullscreen video, which
        // fully detached webView from the window for as long as
        // fullscreen was showing -- firing document.hidden = true
        // into the page the instant fullscreen started. YouTube's
        // player treats that the same as the tab going to the
        // background and reacts by exiting fullscreen again almost
        // immediately, which is what was showing up as fullscreen
        // "blinking and reverting back" on a real device. Keeping
        // webView permanently attached (just visually covered by the
        // fullscreen container on top of it) avoids ever triggering
        // that signal.
        rootLayout = FrameLayout(this)
        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(rootLayout)

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
        // Lets ZOOM_TO_FILL_JS hand the fullscreen stream's own
        // intrinsic width/height back to native code, so the
        // fullscreen orientation can follow the *video's* shape
        // (landscape video -> landscape, portrait/Shorts -> portrait)
        // the way the YouTube app does, rather than just whatever
        // the phone happens to be held as.
        webView.addJavascriptInterface(OrientationBridge(), "ArkTubeOrientation")

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
                // A real (non-SPA) navigation gives YouTube a brand
                // new JS context, which resets ZOOM_TO_FILL_JS's
                // in-memory forceFill flag to its own default
                // (false) -- reassert whatever the user actually
                // last chose so the toggle sticks across navigation,
                // not just within one page's lifetime.
                applyForceFillPreference()
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
                val container = buildFullscreenContainer(view)
                fullscreenContainer = container
                // Added as a second child of rootLayout, on top of
                // webView -- webView itself is never removed/hidden
                // (see the comment on rootLayout's setup in
                // onCreate for why that matters). The container is
                // opaque (it's the video), so this looks identical
                // to swapping the content view outright, without the
                // page-visibility side effect that swapping caused.
                rootLayout.addView(
                    container,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                // Snapshot whatever orientation the activity was
                // actually in right now, so exiting fullscreen snaps
                // back to that -- not a hardcoded default -- even if
                // this isn't the first time fullscreen's been
                // entered this session.
                preFullscreenOrientation = requestedOrientation
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
                // Re-assert the persisted stretch-to-fill choice into
                // this fullscreen session (see the onPageFinished
                // comment above for why the JS-side flag can't be
                // trusted to have survived on its own).
                applyForceFillPreference()
            }

            override fun onHideCustomView() {
                fullscreenContainer?.let { rootLayout.removeView(it) }
                fullscreenContainer?.removeAllViews()
                fullscreenContainer = null
                stretchToggleButton = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                exitImmersiveFullscreen()
                requestedOrientation = preFullscreenOrientation
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

    /**
     * Reasserts immersive fullscreen every time the window regains
     * focus while fullscreen video is showing.
     *
     * This is the actual fix for the settings button (and anything
     * else near the top/bottom edges of the fullscreen player) being
     * unresponsive: Android silently redraws the system bars on any
     * window-focus churn, and opening YouTube's own in-page settings/
     * quality menu causes exactly that kind of brief refocus. Because
     * enterImmersiveFullscreen() uses
     * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE, those reasserted bars
     * only go back into hiding on the *next* interaction elsewhere --
     * so the first tap after the menu opens (i.e. the tap on the menu
     * item, or on the gear icon itself if the same churn happened
     * when it was pressed) lands on the transient status bar sitting
     * in front of the page instead of passing through to it. Simply
     * re-hiding the bars as soon as focus comes back closes that
     * window before the user gets a chance to tap into it.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && customView != null) {
            enterImmersiveFullscreen()
        }
    }

    /**
     * Wraps Chromium's raw fullscreen `video` view in a FrameLayout
     * together with the stretch-to-fill toggle button, and wires up
     * native viewport reporting for it.
     *
     * The button is attached here (as a sibling overlay in the same
     * container) rather than added to the WebView's own DOM, so it
     * keeps working regardless of what the page's JS does and can't
     * be hidden/removed by a YouTube layout change the way an
     * injected in-page element could be.
     */
    private fun buildFullscreenContainer(video: android.view.View): FrameLayout {
        val container = FrameLayout(this)
        container.addView(
            video,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Reports the container's real, native-measured pixel size
        // to the page on every layout pass -- rotation, and system
        // bar/insets changes as immersive mode settles in or a swipe
        // temporarily peeks the bars back. This is the authoritative
        // number ZOOM_TO_FILL_JS should crop against: the DOM's own
        // getBoundingClientRect()/window.innerWidth can still be
        // reporting the *previous* orientation's numbers for a frame
        // or two right after a rotation, which is what let the crop
        // look right in portrait and come out wrong (or not apply at
        // all) after rotating to landscape, or vice versa.
        container.viewTreeObserver.addOnGlobalLayoutListener {
            reportViewportToPage(container.width, container.height)
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            reportViewportToPage(v.width, v.height)
            insets
        }

        val button = buildStretchToggleButton()
        stretchToggleButton = button
        val buttonSizePx = dpToPx(STRETCH_BUTTON_SIZE_DP)
        val marginPx = dpToPx(STRETCH_BUTTON_MARGIN_DP)
        val buttonParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
            // Bottom-left: YouTube's own fullscreen control bar puts
            // its settings/cast/fullscreen-exit icons along the top
            // and bottom-right, and this stays clear of both plus
            // the top inset's swipe-to-reveal strip.
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        container.addView(button, buttonParams)

        return container
    }

    /**
     * Builds the manual stretch-to-fill toggle.
     *
     * ZOOM_TO_FILL_JS's crop is entirely automatic: it only engages
     * itself once it measures real letterbox/pillarbox bars to crop
     * away, and otherwise leaves the frame alone. That logic can be
     * completely correct and still feel like "stretch to fill doesn't
     * work" if there was never actually a UI element for a user to
     * ask for it from -- this button is that missing trigger, exposed
     * as a small persistent overlay for the duration of fullscreen
     * rather than something buried in a menu.
     */
    private fun buildStretchToggleButton(): Button {
        val button = Button(this)
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.textSize = 11f
        button.setPadding(0, 0, 0, 0)
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(STRETCH_BUTTON_BG_COLOR)
        }
        button.setOnClickListener { toggleForceFill() }
        updateStretchButtonAppearance(button)
        return button
    }

    private fun toggleForceFill() {
        forceFillEnabled = !forceFillEnabled
        prefs.edit().putBoolean(PREF_FORCE_FILL, forceFillEnabled).apply()
        applyForceFillPreference()
        stretchToggleButton?.let { updateStretchButtonAppearance(it) }
    }

    private fun updateStretchButtonAppearance(button: Button) {
        button.text = if (forceFillEnabled) "FILL\u2713" else "FILL"
        button.alpha = if (forceFillEnabled) 1f else 0.6f
    }

    /** Pushes the current persisted stretch-to-fill choice into the page's JS. */
    private fun applyForceFillPreference() {
        val enabledJs = if (forceFillEnabled) "true" else "false"
        webView.evaluateJavascript(
            "window.__arktubeSetForceFill && window.__arktubeSetForceFill($enabledJs);",
            null
        )
    }

    /**
     * Hands the fullscreen container's real size (in CSS px, i.e.
     * divided by density the same way the page's own `window.innerWidth`
     * would be) to ZOOM_TO_FILL_JS via __arktubeSetViewport.
     */
    private fun reportViewportToPage(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val density = resources.displayMetrics.density
        val widthCss = widthPx / density
        val heightCss = heightPx / density
        webView.evaluateJavascript(
            "window.__arktubeSetViewport && window.__arktubeSetViewport($widthCss, $heightCss);",
            null
        )
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp.toFloat(),
        resources.displayMetrics
    ).toInt()

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

    // Receives the fullscreen stream's intrinsic width/height from
    // ZOOM_TO_FILL_JS so the activity can rotate to match it, the
    // way the YouTube app does -- a landscape upload gets a
    // landscape-locked fullscreen even if you entered fullscreen
    // holding the phone upright, and vice versa for Shorts/portrait
    // video.
    private inner class OrientationBridge {
        @JavascriptInterface
        fun onFullscreenVideoSize(width: Int, height: Int) {
            runOnUiThread { applyFullscreenOrientation(width, height) }
        }
    }

    /**
     * Locks the activity to whichever orientation matches the
     * fullscreen video's own intrinsic shape -- landscape video gets
     * a landscape-locked fullscreen, portrait/square video gets
     * portrait -- regardless of the phone's system auto-rotate
     * setting. Setting `requestedOrientation` directly is what makes
     * this override the lock: it's an explicit per-activity request,
     * independent of the system auto-rotate toggle (which only
     * governs apps/activities that *haven't* requested a specific
     * orientation). The SENSOR_ variants (rather than plain
     * LANDSCAPE/PORTRAIT) still let the picture flip between the two
     * landscape (or two portrait) rotations as the phone turns, just
     * like the YouTube app -- they just refuse to leave that
     * landscape/portrait pair.
     *
     * No-ops outside of actual fullscreen playback (customView ==
     * null), since a late/duplicate JS callback firing after
     * fullscreen has already been exited shouldn't be able to spin
     * the activity back into a rotation lock.
     */
    private fun applyFullscreenOrientation(videoWidth: Int, videoHeight: Int) {
        if (customView == null || videoWidth <= 0 || videoHeight <= 0) return
        requestedOrientation = if (videoWidth >= videoHeight) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
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

        private const val PREFS_NAME = "arktube_prefs"
        private const val PREF_FORCE_FILL = "force_fill_enabled"

        private const val STRETCH_BUTTON_SIZE_DP = 40
        private const val STRETCH_BUTTON_MARGIN_DP = 16
        private const val STRETCH_BUTTON_BG_COLOR = 0x66000000 // translucent black

        // Crops fullscreen video to fill the screen instead of
        // YouTube's default letterboxed "fit".
        //
        // The old version measured the <video> element's own
        // getBoundingClientRect() and compared *that* box to
        // window.innerWidth/innerHeight. That's the wrong thing to
        // measure: YouTube's fullscreen CSS already stretches the
        // <video> element's *box* to fill the whole screen (with
        // object-fit: contain painting the actual letterboxed/
        // pillarboxed picture *inside* that box) -- so the box was
        // already ~screen-sized, the computed scale came out to
        // ~1.0, and the "zoom to fill" was a no-op. What actually
        // determines how much letterbox padding is being painted is
        // the *stream's own* intrinsic pixel size (video.videoWidth
        // / videoHeight) versus the size of the box it's being fit
        // into -- so that's what's measured now.
        //
        // Scoped to *only* a CSS transform on the <video> -- never
        // its layout box (width/height/object-fit) -- since
        // YouTube's own controls read the video's real box size to
        // position/hit-test themselves; a transform changes what's
        // painted (and, correctly, what taps land on) without
        // touching that box at all, so the controls stay exactly
        // where YouTube put them.
        //
        // Also reports the stream's intrinsic width/height to native
        // (ArkTubeOrientation) so the fullscreen orientation can
        // follow the video's own shape, YouTube-app-style, rather
        // than the phone's.
        //
        // Re-evaluates on fullscreenchange, on resize (covers
        // rotation), loadedmetadata (covers autoplay-next swapping
        // in a differently-shaped video, and covers videoWidth/
        // videoHeight simply not being known yet at the moment
        // fullscreen is entered), and a couple of short delayed
        // retries after entering fullscreen since YouTube sometimes
        // resizes its player again shortly after the fullscreen
        // transition itself. Guarded by a flag on `window` so
        // re-injecting this on every onPageFinished doesn't register
        // duplicate listeners within the same page's JS context.
        private const val ZOOM_TO_FILL_JS = """
            (function() {
                if (window.__arktubeZoomToFillInstalled) { return; }
                window.__arktubeZoomToFillInstalled = true;

                var zoomedVideo = null;
                var lastReportedW = 0;
                var lastReportedH = 0;

                // Manual override, driven by the native
                // stretch-to-fill button -- see
                // buildStretchToggleButton()/toggleForceFill() in
                // MainActivity.kt. Lets a user force the crop even
                // when the automatic measurement below wouldn't have
                // applied one on its own (e.g. an aspect ratio close
                // enough to the container that it fell under the
                // no-op threshold).
                var forceFill = false;
                window.__arktubeSetForceFill = function(enabled) {
                    forceFill = !!enabled;
                    scheduleApplyZoom();
                };

                // Native's own measurement of the fullscreen
                // container, in CSS px -- pushed on every layout pass
                // (rotation, insets settling) via
                // reportViewportToPage()/__arktubeSetViewport in
                // MainActivity.kt. Preferred over
                // getBoundingClientRect()/window.innerWidth below
                // when available, since those can still be reporting
                // the outgoing orientation's numbers for a frame or
                // two right after a rotation -- which is what let
                // this look right in portrait and silently fail (or
                // crop against stale numbers) in landscape.
                var nativeViewport = null;
                window.__arktubeSetViewport = function(widthCss, heightCss) {
                    if (!widthCss || !heightCss) { return; }
                    nativeViewport = { width: widthCss, height: heightCss };
                    scheduleApplyZoom();
                };

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

                function reportOrientation(videoW, videoH) {
                    if (!window.ArkTubeOrientation) { return; }
                    if (videoW === lastReportedW && videoH === lastReportedH) { return; }
                    lastReportedW = videoW;
                    lastReportedH = videoH;
                    window.ArkTubeOrientation.onFullscreenVideoSize(videoW, videoH);
                }

                function applyZoom() {
                    var video = fullscreenVideo();
                    if (zoomedVideo && zoomedVideo !== video) {
                        clearZoom(zoomedVideo);
                        lastReportedW = 0;
                        lastReportedH = 0;
                    }
                    zoomedVideo = video;
                    if (!video) { return; }

                    // Intrinsic size of the decoded frame -- not the
                    // element's own (possibly already screen-sized)
                    // box.
                    var videoW = video.videoWidth;
                    var videoH = video.videoHeight;
                    if (!videoW || !videoH) { return; }

                    reportOrientation(videoW, videoH);

                    // The box that actually constrains the painted
                    // picture: the fullscreen element itself, not
                    // necessarily the <video>'s own box.
                    var container = document.fullscreenElement || document.webkitFullscreenElement || video;
                    var crect = container.getBoundingClientRect();
                    // nativeViewport first (see the comment where
                    // it's declared above); crect/window.inner* are
                    // just the fallback for before native's first
                    // layout pass has reported in.
                    var containerW = (nativeViewport && nativeViewport.width) || crect.width || window.innerWidth;
                    var containerH = (nativeViewport && nativeViewport.height) || crect.height || window.innerHeight;
                    if (!containerW || !containerH) { return; }

                    var videoAspect = videoW / videoH;
                    var containerAspect = containerW / containerH;

                    // Size the picture is actually being painted at
                    // under object-fit: contain (the letterboxed/
                    // pillarboxed rect), then scale up so its short
                    // side matches the container -- object-fit:
                    // cover, effectively, done via transform instead
                    // of object-fit so hit-testing math is untouched.
                    var fittedW, fittedH;
                    if (videoAspect > containerAspect) {
                        fittedW = containerW;
                        fittedH = containerW / videoAspect;
                    } else {
                        fittedH = containerH;
                        fittedW = containerH * videoAspect;
                    }

                    var scale = Math.max(containerW / fittedW, containerH / fittedH);

                    // forceFill lowers the no-op threshold from
                    // "letterbox big enough to bother cropping" down
                    // to "basically any letterbox at all", so an
                    // explicit user request still does something
                    // even for an aspect ratio that's a near-exact
                    // match already.
                    var threshold = forceFill ? 1.001 : 1.01;
                    if (!isFinite(scale) || scale <= threshold) {
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
                // 'loadedmetadata' is when videoWidth/videoHeight
                // first become non-zero, and fires again if YouTube
                // swaps in a new <video> src (autoplay-next) without
                // a fresh fullscreenchange event.
                document.addEventListener('loadedmetadata', function(e) {
                    if (e.target === fullscreenVideo()) {
                        scheduleApplyZoom();
                    }
                }, true);
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
