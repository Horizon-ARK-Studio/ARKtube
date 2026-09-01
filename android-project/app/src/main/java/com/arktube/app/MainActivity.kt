package com.arktube.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
 *  - reports the device's real, actual viewport size/density to the
 *    page instead of WebView's legacy fixed-width default (see
 *    useWideViewPort/loadWithOverviewMode in onCreate()), so
 *    m.youtube.com's own responsive layout renders its phone
 *    breakpoint (full-bleed player) rather than misreading a fixed
 *    ~980px legacy layout width as tablet-sized and switching to its
 *    desktop-style sidebar layout
 *  - lets in-app back navigation walk the WebView's own history first
 *  - allows fullscreen video (WebChromeClient's
 *    on/onHideCustomView) since YouTube's HTML5 player needs it for
 *    the fullscreen button to do anything
 *  - zooms fullscreen video to fill the screen (cropping any
 *    letterbox/pillarbox bars) instead of leaving YouTube's default
 *    letterboxed "fit" framing -- see applyNativeZoomCrop() below.
 *    Two earlier attempts at this (forcing the <video> element's
 *    layout box to fill the screen, then a CSS transform scoped to
 *    just its paint layer) both turned out to be fixing the wrong
 *    layer entirely: once YouTube's player goes fullscreen, WebView
 *    doesn't keep rendering a <video> through the normal DOM/CSS
 *    pipeline at all -- it hands the app a separate native View via
 *    WebChromeClient.onShowCustomView(), backed by its own
 *    hardware-composited SurfaceView, entirely outside the page's
 *    DOM. No CSS transform or object-fit rule reaches that surface,
 *    which is why both earlier attempts visibly did nothing (or, in
 *    the layout-box version, desynced YouTube's own click-hit-testing
 *    math and upscaled the decoded frame past its native resolution --
 *    "blurry and nothing's clickable"). The crop has to happen
 *    natively instead, as View.scaleX/scaleY on the actual customView
 *    Chromium handed us -- since the letterboxed picture (bars
 *    included) is baked into that View's own paint, scaling the whole
 *    View up uniformly crops the bars out the same way the CSS
 *    transform was meant to, just on the layer that's really on
 *    screen. The scale factor is still computed from the video's own
 *    intrinsic pixel size (video.videoWidth/videoHeight, read via JS
 *    since that's DOM-only information the native side has no other
 *    way to see) versus the customView's native-measured size --
 *    see the ArkTubeOrientation bridge and applyNativeZoomCrop().
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
 *    ArkTubeOrientation calls inside VIDEO_SIZE_REPORT_JS
 *  - keeps the status/nav bar color in sync with whichever theme
 *    YouTube itself is rendering (its own light/dark toggle, not the
 *    phone's system theme) -- see THEME_SYNC_JS and ThemeBridge below
 *  - hides YouTube's "open app" nag button/banner (nudging you at the
 *    native YouTube app instead) since this app already *is* that
 *    experience wrapped natively -- see HIDE_OPEN_APP_JS below
 *  - offers a manual "stretch to fill" toggle over fullscreen video,
 *    since the automatic crop-to-fill in applyNativeZoomCrop() only
 *    engages itself when it measures real letterbox/pillarbox bars
 *    to crop -- there was previously no way for a user to *ask* for
 *    it, only for the code to decide on its own -- see
 *    buildStretchToggleButton()/toggleForceFill(), both of which
 *    just feed the forceFillEnabled field applyNativeZoomCrop()
 *    itself reads
 *  - reasserts immersive mode on every window-focus regain while
 *    fullscreen video is showing (see onWindowFocusChanged), since
 *    Android silently redraws the system bars on any focus churn --
 *    including the brief refocus YouTube's own in-page settings/
 *    quality menu causes when it opens -- and a stray transient bar
 *    sitting in front of the page is what was swallowing the tap
 *    meant for that menu (or its gear icon) instead of passing it
 *    through
 *  - recomputes the native zoom-to-fill crop on every layout pass
 *    (rotation, insets settling) against the customView's own
 *    native-measured size -- see applyNativeZoomCrop() and where
 *    it's called from buildFullscreenContainer()'s layout/insets
 *    listeners -- so the crop always has an immediately-current
 *    number to work from instead of racing a DOM measurement that
 *    can still reflect the outgoing orientation for a frame or two
 *    right after a rotation
 *  - forces the *normal* (non-fullscreen) page to re-sync any
 *    already-rendered off-screen content -- most visibly the "Up
 *    next"/related-videos row -- to the WebView's real width after a
 *    rotation, instead of leaving it stuck at the pre-rotation width
 *    until the user happens to scroll it into view -- see
 *    onConfigurationChanged()/forceLayoutReflow()
 *  - exposes the currently playing video to the rest of the OS as a
 *    real MediaSessionCompat, so play/pause/seek/skip reach it from
 *    outside the app entirely: the lock screen, the notification
 *    shade, a wired headset's inline remote, a Bluetooth
 *    earbud/car-stereo's AVRCP buttons, a paired watch -- anything
 *    the platform considers "a device that can control the active
 *    media session". MEDIA_SESSION_JS watches the page's own <video>
 *    element (play/pause/seeked/timeupdate/loadedmetadata) and
 *    reports state/title/artwork back over the ArkTubeMediaPlayback
 *    bridge; MediaPlaybackService owns the actual MediaSessionCompat,
 *    the MediaStyle notification, and audio-focus/becoming-noisy
 *    handling, translating session callbacks back into JS calls on
 *    that same <video> element via MainActivity's
 *    MediaPlaybackService.CommandListener implementation. Runs as a
 *    bound (not yet foreground) service from onCreate, and is only
 *    promoted into the foreground -- posting the actual notification
 *    -- the first time the page reports real playback, not eagerly on
 *    launch, so there's never a "nothing's playing" notification
 *    sitting in the shade. See buildMediaPlaybackService... calls in
 *    onCreate/onDestroy and MediaPlaybackService.kt.
 *
 * Explicitly out of scope even with the above (future stages, see the
 * repo-root roadmap): a persistent nav shell/sidebar, download
 * interception, PiP, a real playlist/queue or Android Auto browsing,
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

    // The fullscreen stream's own intrinsic pixel size, as last
    // reported by VIDEO_SIZE_REPORT_JS via the ArkTubeOrientation
    // bridge (see onFullscreenVideoSize below). This is DOM-only
    // information -- video.videoWidth/videoHeight -- that native code
    // has no other way to observe, so JS is still the source for it;
    // everything downstream of it (the actual crop) is native. Reset
    // to 0 whenever fullscreen exits so a stale size can't leak into
    // the next session before its own first report arrives.
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    // MediaPlaybackService owns the actual MediaSessionCompat/
    // notification; MainActivity just binds to it so the two can pass
    // messages both ways -- see the class doc's MediaSessionCompat
    // bullet, mediaServiceConnection, and mediaCommandListener below.
    private var mediaService: MediaPlaybackService? = null
    private var mediaServiceBound = false

    private val mediaServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as MediaPlaybackService.LocalBinder).service
            mediaService = service
            service.setCommandListener(mediaCommandListener)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // The system only calls this on an actual crash of the
            // service's process, not on our own unbindService() call
            // in onDestroy() -- but null it out regardless so a
            // stray late callback can't reach a dead binder.
            mediaService = null
        }
    }

    // Translates MediaSessionCompat callbacks (from the lock screen,
    // a Bluetooth headset's AVRCP buttons, the notification's own
    // transport controls, etc.) into JS calls against the page's real
    // <video> element -- the actual thing playing. See
    // MEDIA_CONTROL_PLAY_JS and friends below for what each of these
    // runs.
    private val mediaCommandListener = object : MediaPlaybackService.CommandListener {
        override fun onPlayCommand() {
            runOnUiThread { webView.evaluateJavascript(MEDIA_CONTROL_PLAY_JS, null) }
        }

        override fun onPauseCommand() {
            runOnUiThread { webView.evaluateJavascript(MEDIA_CONTROL_PAUSE_JS, null) }
        }

        override fun onSeekToCommand(positionMs: Long) {
            runOnUiThread { webView.evaluateJavascript(mediaControlSeekJs(positionMs), null) }
        }

        override fun onFastForwardCommand() {
            runOnUiThread { webView.evaluateJavascript(mediaControlSkipJs(SEEK_STEP_SECONDS), null) }
        }

        override fun onRewindCommand() {
            runOnUiThread { webView.evaluateJavascript(mediaControlSkipJs(-SEEK_STEP_SECONDS), null) }
        }
    }

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
        // Off by default, which is the actual root cause of the
        // "sidebar squeezed everything" layout bug: with this off,
        // WebView ignores the page's own <meta name="viewport"> tag
        // entirely and lays it out at a fixed legacy width (~980 CSS
        // px) zoomed out to fit, regardless of the device's real
        // screen size or orientation -- so window.innerWidth inside
        // the page never reflects reality, m.youtube.com reads that
        // fixed ~980px as "tablet-width", and renders its two-column
        // desktop-style watch layout (small player + sidebar of
        // related videos) instead of the full-bleed phone layout,
        // in *both* orientations. Turning it on makes the WebView
        // report the device's actual CSS viewport width/density to
        // the page (i.e. what device-width actually resolves to),
        // which is the "dynamically get [the real size] from the
        // client" fix -- not a fixed DPI/zoom override, just letting
        // the page see the truth about the screen it's actually on.
        webView.settings.useWideViewPort = true
        // Companion setting: without this, content initially loads
        // zoomed out to show the whole (now correctly wide) layout
        // before settling, producing a visible snap/jump on first
        // paint. This starts it already at "fit the viewport" scale.
        webView.settings.loadWithOverviewMode = true
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
        // Lets VIDEO_SIZE_REPORT_JS hand the fullscreen stream's own
        // intrinsic width/height back to native code, so the
        // fullscreen orientation can follow the *video's* shape
        // (landscape video -> landscape, portrait/Shorts -> portrait)
        // the way the YouTube app does, rather than just whatever
        // the phone happens to be held as -- and so the native
        // zoom-to-fill crop in applyNativeZoomCrop() has the video's
        // intrinsic size to work from.
        webView.addJavascriptInterface(OrientationBridge(), "ArkTubeOrientation")
        // Lets MEDIA_SESSION_JS hand the page's actual <video>
        // play/pause/seek state and title/artwork back to native
        // code, so MediaPlaybackService's MediaSessionCompat -- and
        // therefore the lock screen, notification, and any connected
        // Bluetooth/wired transport controls -- stays truthful about
        // what's really happening on the page, not just a mirror of
        // whatever command was last sent to it (the user can just as
        // well hit YouTube's own on-page pause button).
        webView.addJavascriptInterface(MediaPlaybackBridge(), "ArkTubeMediaPlayback")

        webView.webViewClient = object : WebViewClient() {
            // YouTube's fullscreen button doesn't hand the WebView a
            // bare <video>; it puts the player into the page's own
            // Fullscreen API, and WebChromeClient.onShowCustomView
            // mirrors that DOM state natively by handing over a
            // separate customView. The "fit" vs. "fill" framing
            // itself is applied natively (applyNativeZoomCrop()) once
            // that customView exists -- all this JS still needs to do
            // is keep reporting the video's own intrinsic size, so we
            // reinstall it here on every page load, ready before the
            // fullscreen button is ever tapped.
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(VIDEO_SIZE_REPORT_JS, null)
                view.evaluateJavascript(THEME_SYNC_JS, null)
                view.evaluateJavascript(HIDE_OPEN_APP_JS, null)
                // MEDIA_SESSION_JS deliberately does NOT live inside
                // the fullscreen-only customView path -- the actual
                // <video> element it watches stays in the DOM and
                // keeps firing play/pause/timeupdate regardless of
                // whether fullscreen (a purely native mirror of that
                // same element) is active, so installing it once per
                // page load here covers both in-page and fullscreen
                // playback with the same listeners.
                view.evaluateJavascript(MEDIA_SESSION_JS, null)
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
                // Must happen before this View is ever attached/drawn:
                // see neutralizeSurfaceViewZOrder()'s own doc comment
                // for why any SurfaceView buried inside it otherwise
                // paints above every normal View in the window --
                // including our own stretch-to-fill button -- no
                // matter where that button sits in the layout.
                neutralizeSurfaceViewZOrder(view)
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
                // The stretch-to-fill button is deliberately *not* a
                // child of `container` -- see attachStretchToggleButton()'s
                // doc comment for why it's added straight to
                // rootLayout as its own top-level sibling instead.
                attachStretchToggleButton()
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
                // Safety net: VIDEO_SIZE_REPORT_JS should already be
                // installed from onPageFinished, but this covers the
                // (unlikely) case fullscreen was reached before that
                // fired. Harmless to call again either way -- it's
                // guarded against installing its listeners twice.
                webView.evaluateJavascript(VIDEO_SIZE_REPORT_JS, null)
                // customView hasn't been laid out yet at this exact
                // point (width/height are still 0), so this mostly
                // no-ops here -- the real first crop application
                // happens off the container's own first global-layout
                // pass, or off the video-size report if that arrives
                // second. Calling it here too just covers the case
                // both already have stale data from a previous
                // session's fields.
                applyNativeZoomCrop()
            }

            override fun onHideCustomView() {
                fullscreenContainer?.let { rootLayout.removeView(it) }
                fullscreenContainer?.removeAllViews()
                fullscreenContainer = null
                stretchToggleButton?.let { rootLayout.removeView(it) }
                stretchToggleButton = null
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                lastVideoWidth = 0
                lastVideoHeight = 0
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                exitImmersiveFullscreen()
                requestedOrientation = preFullscreenOrientation
            }
        }

        webView.loadUrl(SITE_URL)

        // Only a *bound* (not yet foreground) service at this point --
        // no notification exists until MediaPlaybackBridge reports
        // real playback starting, in onPlaybackState() below. Binding
        // this early just gets mediaCommandListener wired up before
        // the user could possibly reach a play button.
        bindService(
            Intent(this, MediaPlaybackService::class.java),
            mediaServiceConnection,
            Context.BIND_AUTO_CREATE
        )
        mediaServiceBound = true

        // Notification permission is only needed to actually *show*
        // the media notification (Android 13+) -- MediaSessionCompat
        // itself, and therefore lock-screen/Bluetooth/wired-headset
        // transport control, works regardless of whether this is
        // granted, so there's nothing else gated on the result.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * Tears down the MediaPlaybackService binding/lifecycle.
     *
     * unbindService() alone isn't enough cleanup: once
     * onPlaybackState() below has called startForegroundService() at
     * least once, the service is independently "started" and outlives
     * being unbound (that's what lets it survive brief Activity
     * recreation) -- so this also explicitly stops it, same as
     * bindService()'s BIND_AUTO_CREATE is paired with an explicit
     * unbind rather than relying on either side to infer the other.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (mediaServiceBound) {
            mediaService?.setCommandListener(null)
            unbindService(mediaServiceConnection)
            mediaServiceBound = false
        }
        stopService(Intent(this, MediaPlaybackService::class.java))
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
     * Handles rotation for the *normal* (non-fullscreen) page.
     *
     * AndroidManifest.xml declares
     * `android:configChanges="orientation|screenSize|keyboardHidden"`
     * on this Activity specifically so a rotation doesn't tear down
     * and recreate it (which would reload the WebView from scratch,
     * losing scroll position and briefly flashing a blank page) --
     * but that also means nothing else runs automatically on
     * rotation unless it's hooked here. Android still resizes
     * webView/rootLayout's own views correctly on its own (they're
     * MATCH_PARENT), and WebView's base View class already forwards
     * that resize down into Chromium -- so *visible*, currently
     * on-screen content reflows to the new width fine without any of
     * this. What doesn't reflow on its own is content that was
     * already rendered *off-screen* before the rotation (most
     * visibly: further rows of "Up next"/related videos below the
     * fold) -- it stays laid out at the stale pre-rotation width
     * until the user manually scrolls it into view, at which point
     * it snaps to the correct width. See forceLayoutReflow() for why
     * and how that's worked around; this override just makes sure it
     * actually runs on every rotation, not just the ones that happen
     * to coincide with something else already touching the page.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        forceLayoutReflow()
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
     * Forces YouTube's own page JS to re-sync off-screen list items
     * (most visibly the "Up next"/related-videos row) to the
     * WebView's real, current width after a rotation.
     *
     * The bug this works around: a real browser window firing a
     * resize continuously dispatches DOM 'resize' (and
     * 'orientationchange') events as it happens, which is what
     * YouTube's own mobile web layout listens for to know when to
     * recompute anything it's deferred -- including re-measuring
     * list items it previously decided not to bother laying out
     * because they were off-screen (a legitimate perf optimization on
     * their end, not a bug in their code). Android's WebView, when
     * *its own View* is resized by the framework (as happens here
     * via rootLayout/webView both being MATCH_PARENT, so a config
     * change just resizes them in place), does correctly forward the
     * new size into Chromium's layout engine -- but doesn't reliably
     * guarantee those specific 'resize'/'orientationchange' DOM
     * events fire for page JS the way an actual browser window resize
     * does. So the visible viewport reflows correctly (Chromium's own
     * layout knows the truth), but page JS that's specifically
     * listening for those events to decide when to re-measure
     * something never gets told to -- which is exactly the "stuck at
     * the old width until you scroll" symptom: scrolling works
     * because it fires its own event (a 'scroll'/intersection
     * callback) that happens to trigger the same re-measurement path.
     *
     * Two-part fix, so either path YouTube's own code might be
     * listening on gets covered:
     *  1. Explicitly dispatch synthetic 'resize' and
     *     'orientationchange' events on `window`.
     *  2. Nudge the WebView's own scroll position by a pixel and
     *     immediately back. This is the same trigger scrolling does
     *     manually (per the observed bug), and is a deliberate
     *     belt-and-braces alongside (1): if YouTube's related-videos
     *     row happens to be driven by an IntersectionObserver or
     *     scroll listener instead of (or in addition to) a resize
     *     listener, this covers that path too, without requiring a
     *     real user gesture.
     *
     * Run at a few short delays rather than once: rotation, inset
     * changes (status/nav bar re-layout), and WebView's own internal
     * resize don't necessarily all finish landing in the same frame,
     * so a single immediate call can fire before Chromium has
     * actually finished laying out the new size -- the delayed
     * follow-ups catch that.
     *
     * No-ops during fullscreen video: the customView's own
     * orientation/crop handling (applyFullscreenOrientation()/
     * applyNativeZoomCrop()) already owns rotation while it's
     * showing, and scrolling the WebView underneath it would be
     * pointless (it's fully covered) as well as pointing scroll
     * position at content the user isn't looking at.
     */
    private fun forceLayoutReflow() {
        if (customView != null) return
        for (delayMs in REFLOW_RETRY_DELAYS_MS) {
            webView.postDelayed({
                if (customView != null) return@postDelayed
                webView.evaluateJavascript(FORCE_REFLOW_JS, null)
                webView.scrollBy(0, 1)
                webView.scrollBy(0, -1)
            }, delayMs)
        }
    }

    /**
     * Wraps Chromium's raw fullscreen `video` view in a FrameLayout
     * on its own, and wires up applyNativeZoomCrop() to recompute on
     * every layout pass.
     *
     * The stretch-to-fill button used to be added here too, as a
     * sibling overlay inside this same container. That doesn't
     * actually guarantee it draws (or receives touches) on top of
     * `video`: see neutralizeSurfaceViewZOrder() for why a
     * hardware-composited SurfaceView can paint above every normal
     * View in the window regardless of sibling order, and
     * attachStretchToggleButton() for where the button lives now
     * instead.
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

        // Recomputes the native zoom-to-fill crop on every layout
        // pass -- rotation, and system bar/insets changes as
        // immersive mode settles in or a swipe temporarily peeks the
        // bars back. applyNativeZoomCrop() reads the customView's own
        // native-measured width/height directly (no DOM round-trip),
        // which is the authoritative, immediately-current number: a
        // DOM measurement like getBoundingClientRect()/
        // window.innerWidth can still report the *previous*
        // orientation's numbers for a frame or two right after a
        // rotation, which is what let the crop look right in portrait
        // and come out wrong (or not apply at all) after rotating to
        // landscape, or vice versa.
        container.viewTreeObserver.addOnGlobalLayoutListener {
            applyNativeZoomCrop()
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            applyNativeZoomCrop()
            insets
        }

        return container
    }

    /**
     * Recursively strips any SurfaceView inside `root` of the
     * z-order flags that let it paint above the rest of the window.
     *
     * This is the actual fix for the stretch-to-fill button (and any
     * other native overlay) being invisible/untouchable once
     * fullscreen video is on screen: Chromium's fullscreen customView
     * is backed by a hardware-composited SurfaceView, and WebView
     * commonly leaves setZOrderOnTop (sometimes setZOrderMediaOverlay)
     * enabled on it for efficient video compositing. Either flag
     * moves that SurfaceView's *actual* pixels into a system-level
     * compositor layer that sits above the entire window's normal
     * View hierarchy -- not just above `video`'s siblings in whatever
     * container we put it in. Normal Android z-order (draw/add order
     * within a ViewGroup) has no say over that layer at all, so no
     * amount of rearranging our button in the layout could ever put
     * it on top while the flag stayed set. This didn't show up before
     * the crop because the video only ever covered part of the
     * screen (letterboxed); now that force-fill/crop routinely makes
     * it fill the whole screen, it sits in front of everything,
     * including the very button meant to control it.
     *
     * Turning both flags off drops the SurfaceView back to its
     * default "hole punch" compositing, drawn in the window's normal
     * surface at its actual position in the View tree -- exactly
     * where addView() put it, i.e. *below* the button added after it.
     * That also restores normal View-hierarchy touch dispatch, which
     * was never actually about the button (dispatch already followed
     * add-order correctly) but effectively didn't matter while the
     * button wasn't visibly reachable to tap in the first place.
     *
     * Must run before `root` is attached to the window -- the flags
     * take effect based on the SurfaceView's state as its Surface is
     * created, not continuously, so setting them post-attach can be a
     * frame or two late.
     */
    private fun neutralizeSurfaceViewZOrder(root: android.view.View) {
        if (root is SurfaceView) {
            root.setZOrderOnTop(false)
            root.setZOrderMediaOverlay(false)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                neutralizeSurfaceViewZOrder(root.getChildAt(i))
            }
        }
    }

    /**
     * Adds the stretch-to-fill toggle directly to rootLayout, as its
     * own top-level sibling *above* fullscreenContainer -- not nested
     * inside it.
     *
     * neutralizeSurfaceViewZOrder() is what actually makes this
     * button paint/receive touches above the video (see its own doc
     * comment); this placement is a second, independent guarantee on
     * top of that: even if some future customView nests the button's
     * container in a way that confuses normal draw order, adding the
     * button as the *last* child of rootLayout itself -- the same
     * root FrameLayout fullscreenContainer is a child of -- keeps it
     * unambiguously topmost at the window's own top level, decoupled
     * from whatever fullscreenContainer's internal contents do.
     */
    private fun attachStretchToggleButton() {
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
        rootLayout.addView(button, buttonParams)
    }

    /**
     * Builds the manual stretch-to-fill toggle.
     *
     * applyNativeZoomCrop()'s crop is entirely automatic: it only
     * engages itself once it measures real letterbox/pillarbox bars
     * to crop away, and otherwise leaves the frame alone. That logic
     * can be completely correct and still feel like "stretch to fill
     * doesn't work" if there was never actually a UI element for a
     * user to ask for it from -- this button is that missing trigger,
     * exposed as a small persistent overlay for the duration of
     * fullscreen rather than something buried in a menu.
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
        // forceFillEnabled is read directly by applyNativeZoomCrop()
        // (it's a plain field on this Activity, not something that
        // has to cross the JS bridge) -- just recompute the crop with
        // the new value in effect.
        applyNativeZoomCrop()
        stretchToggleButton?.let { updateStretchButtonAppearance(it) }
    }

    private fun updateStretchButtonAppearance(button: Button) {
        button.text = if (forceFillEnabled) "FILL\u2713" else "FILL"
        button.alpha = if (forceFillEnabled) 1f else 0.6f
    }

    /**
     * The actual zoom-to-fill crop: scales the native customView
     * Chromium handed us in onShowCustomView up around its own
     * center until its short axis matches the fullscreen container,
     * cropping away whatever letterbox/pillarbox bars YouTube's
     * player painted into that View. This -- View.scaleX/scaleY on
     * the real on-screen View -- is the fix; see the onCreate class
     * doc and onShowCustomView's own comments for why a CSS
     * transform on the page's <video> element (tried previously)
     * never touches this layer at all.
     *
     * Safe to call at any time, including well outside fullscreen or
     * before either the container has been laid out or a video size
     * has been reported yet -- it no-ops until both are available,
     * and is deliberately called from several different triggers
     * (onShowCustomView, every layout/insets pass, every new video
     * size report, and the manual force-fill toggle) since any one of
     * them can be the one that's actually ready first.
     */
    private fun applyNativeZoomCrop() {
        val view = customView ?: return
        if (lastVideoWidth <= 0 || lastVideoHeight <= 0) return
        val containerW = view.width
        val containerH = view.height
        if (containerW <= 0 || containerH <= 0) return

        val videoAspect = lastVideoWidth.toFloat() / lastVideoHeight.toFloat()
        val containerAspect = containerW.toFloat() / containerH.toFloat()

        // Size the picture is actually being painted at under
        // whatever "fit" framing YouTube's player used (the
        // letterboxed/pillarboxed rect), then scale up so its short
        // side matches the container -- a crop-to-fill, computed
        // from the stream's own intrinsic pixel size vs. the
        // container's real native-measured size, not any DOM box.
        val fittedW: Float
        val fittedH: Float
        if (videoAspect > containerAspect) {
            fittedW = containerW.toFloat()
            fittedH = containerW / videoAspect
        } else {
            fittedH = containerH.toFloat()
            fittedW = containerH * videoAspect
        }
        if (fittedW <= 0f || fittedH <= 0f) return

        val scale = maxOf(containerW / fittedW, containerH / fittedH)

        // forceFillEnabled lowers the no-op threshold from
        // "letterbox big enough to bother cropping" down to
        // "basically any letterbox at all", so an explicit user
        // request still does something even for an aspect ratio
        // that's a near-exact match already.
        val threshold = if (forceFillEnabled) 1.001f else 1.01f
        if (!scale.isFinite() || scale <= threshold) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        view.pivotX = containerW / 2f
        view.pivotY = containerH / 2f
        view.scaleX = scale
        view.scaleY = scale
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
    // VIDEO_SIZE_REPORT_JS -- the one piece of this whole feature
    // that genuinely has to come from JS, since video.videoWidth/
    // videoHeight is DOM-only information native code can't observe
    // any other way. Everything downstream of it is native: used
    // both to rotate the activity to match the video's own shape (the
    // way the YouTube app does -- landscape upload gets a
    // landscape-locked fullscreen even if you entered fullscreen
    // holding the phone upright, and vice versa for Shorts/portrait
    // video) and to drive the native zoom-to-fill crop in
    // applyNativeZoomCrop().
    private inner class OrientationBridge {
        @JavascriptInterface
        fun onFullscreenVideoSize(width: Int, height: Int) {
            runOnUiThread {
                lastVideoWidth = width
                lastVideoHeight = height
                applyFullscreenOrientation(width, height)
                applyNativeZoomCrop()
            }
        }
    }

    // Receives play/pause/seek state and title/artwork from
    // MEDIA_SESSION_JS, watching the page's own <video> element --
    // the one piece of this feature that has to come from JS, since
    // "is this <video> actually playing right now" and "what's its
    // title" are DOM-only facts native code has no other way to
    // observe. Everything downstream (the actual MediaSessionCompat,
    // notification, audio focus) lives in MediaPlaybackService.
    private inner class MediaPlaybackBridge {
        @JavascriptInterface
        fun onPlaybackState(isPlaying: Boolean, positionMs: Long, playbackRate: Float) {
            runOnUiThread {
                if (isPlaying) {
                    // Promotes the already-bound service into the
                    // foreground -- and therefore posts the actual
                    // notification -- the first time real playback is
                    // reported, not eagerly back in onCreate. Safe to
                    // call again on every subsequent play too:
                    // starting an already-running service just
                    // re-delivers onStartCommand.
                    ContextCompat.startForegroundService(
                        this@MainActivity, Intent(this@MainActivity, MediaPlaybackService::class.java)
                    )
                }
                mediaService?.updatePlaybackState(isPlaying, positionMs, playbackRate)
            }
        }

        @JavascriptInterface
        fun onMediaInfo(title: String?, durationMs: Long, artworkUrl: String?) {
            runOnUiThread { loadArtworkAndApplyMetadata(title, durationMs, artworkUrl) }
        }
    }

    /**
     * Fetches `artworkUrl` (the page's own og:image, i.e. the video's
     * thumbnail) off the main thread and hands the decoded Bitmap --
     * or null if there wasn't one or it failed to load -- to
     * MediaPlaybackService along with the rest of the metadata, for
     * the lock screen/notification's album-art slot.
     *
     * Deliberately tolerant of failure (bad/missing URL, network
     * error, decode failure): artwork is a nice-to-have for the
     * notification, not something that should ever block title/
     * duration from reaching the session.
     */
    private fun loadArtworkAndApplyMetadata(title: String?, durationMs: Long, artworkUrl: String?) {
        val service = mediaService ?: return
        if (artworkUrl.isNullOrBlank()) {
            service.updateMetadata(title, durationMs, null)
            return
        }
        Thread {
            val artwork = try {
                java.net.URL(artworkUrl).openStream().use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread { mediaService?.updateMetadata(title, durationMs, artwork) }
        }.start()
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

        // Retry schedule for forceLayoutReflow() -- see its own doc
        // comment for why a single immediate call isn't enough
        // (rotation/inset settling can still be in progress).
        private val REFLOW_RETRY_DELAYS_MS = longArrayOf(0L, 150L, 400L)

        // Dispatches synthetic resize/orientationchange events so any
        // of YouTube's own listeners for those *specific* DOM events
        // fire, even though the WebView's own resize (a real, correct
        // Chromium layout change) doesn't reliably trigger them the
        // way an actual browser window resize would. See
        // forceLayoutReflow()'s doc comment in MainActivity.kt for
        // the full explanation -- the small scroll nudge that's the
        // other half of that fix happens natively (webView.scrollBy),
        // not here, since it needs to move the *Android* View's
        // scroll position, not just fire a page-side event.
        private const val FORCE_REFLOW_JS = """
            (function() {
                window.dispatchEvent(new Event('resize'));
                window.dispatchEvent(new Event('orientationchange'));
            })();
        """

        // Reports the fullscreen stream's own intrinsic pixel size
        // (video.videoWidth/videoHeight) to native code
        // (ArkTubeOrientation) whenever it changes. This is *all*
        // this JS does now -- it no longer attempts to crop anything
        // itself.
        //
        // An earlier version tried to do the actual zoom-to-fill crop
        // right here, via a CSS transform on the <video> element. It
        // never worked on a real device: once YouTube's player goes
        // fullscreen, WebView promotes it out of the DOM entirely
        // onto WebChromeClient's customView -- a separate,
        // hardware-composited native View/SurfaceView that this page's
        // CSS has no way to reach at all. Styling <video> here was
        // styling a DOM node that, from that point on, isn't what's
        // actually on screen -- so the transform was silently inert
        // regardless of how correct its math was. (A version before
        // that tried resizing the <video> element's own layout box
        // instead of just its paint layer, which does affect what's
        // on screen in *non*-fullscreen playback, but for the same
        // reason has no effect once fullscreen hands rendering off to
        // customView either.)
        //
        // The crop itself now lives entirely in Kotlin -- see
        // applyNativeZoomCrop() -- as View.scaleX/scaleY on the real
        // customView. video.videoWidth/videoHeight is the one piece
        // of that math this JS still has to supply, since it's
        // DOM-only information with no native equivalent.
        //
        // Re-evaluates on fullscreenchange, on resize (covers
        // rotation), loadedmetadata (covers autoplay-next swapping in
        // a differently-shaped video, and covers videoWidth/
        // videoHeight simply not being known yet at the moment
        // fullscreen is entered), and a couple of short delayed
        // retries after entering fullscreen since YouTube sometimes
        // resizes its player again shortly after the fullscreen
        // transition itself. Guarded by a flag on `window` so
        // re-injecting this on every onPageFinished doesn't register
        // duplicate listeners within the same page's JS context.
        private const val VIDEO_SIZE_REPORT_JS = """
            (function() {
                if (window.__arktubeVideoSizeReportInstalled) { return; }
                window.__arktubeVideoSizeReportInstalled = true;

                var lastReportedW = 0;
                var lastReportedH = 0;

                function fullscreenVideo() {
                    var el = document.fullscreenElement || document.webkitFullscreenElement;
                    if (!el) { return null; }
                    return el.tagName === 'VIDEO' ? el : el.querySelector('video');
                }

                function reportSize() {
                    var video = fullscreenVideo();
                    if (!video) { return; }

                    // Intrinsic size of the decoded frame -- not the
                    // element's own (possibly already screen-sized)
                    // box.
                    var videoW = video.videoWidth;
                    var videoH = video.videoHeight;
                    if (!videoW || !videoH) { return; }
                    if (videoW === lastReportedW && videoH === lastReportedH) { return; }
                    lastReportedW = videoW;
                    lastReportedH = videoH;
                    if (window.ArkTubeOrientation) {
                        window.ArkTubeOrientation.onFullscreenVideoSize(videoW, videoH);
                    }
                }

                var pending = false;
                function scheduleReportSize() {
                    if (pending) { return; }
                    pending = true;
                    requestAnimationFrame(function() {
                        pending = false;
                        reportSize();
                    });
                }

                document.addEventListener('fullscreenchange', scheduleReportSize);
                document.addEventListener('webkitfullscreenchange', scheduleReportSize);
                window.addEventListener('resize', scheduleReportSize);
                document.addEventListener('fullscreenchange', function() {
                    // A fresh fullscreen session (even for the same
                    // video src, e.g. re-entering fullscreen) should
                    // re-report once its size is known again, not
                    // stay deduped against whatever the previous
                    // session last reported.
                    lastReportedW = 0;
                    lastReportedH = 0;
                    setTimeout(scheduleReportSize, 300);
                    setTimeout(scheduleReportSize, 1000);
                });
                // 'loadedmetadata' is when videoWidth/videoHeight
                // first become non-zero, and fires again if YouTube
                // swaps in a new <video> src (autoplay-next) without
                // a fresh fullscreenchange event.
                document.addEventListener('loadedmetadata', function(e) {
                    if (e.target === fullscreenVideo()) {
                        scheduleReportSize();
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

        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001

        // How far a native fast-forward/rewind transport command (the
        // notification's/lock screen's skip buttons) moves the
        // playhead. 10s matches the skip amount most media apps and
        // the platform's own default RemoteControlClient behavior use.
        private const val SEEK_STEP_SECONDS = 10

        private const val MEDIA_CONTROL_PLAY_JS = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.play(); }
            })();
        """

        private const val MEDIA_CONTROL_PAUSE_JS = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.pause(); }
            })();
        """

        private fun mediaControlSeekJs(positionMs: Long): String = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.currentTime = ${positionMs / 1000.0}; }
            })();
        """

        private fun mediaControlSkipJs(deltaSeconds: Int): String = """
            (function() {
                var v = document.querySelector('video');
                if (v) { v.currentTime = Math.max(0, v.currentTime + ($deltaSeconds)); }
            })();
        """

        // Watches whichever <video> element YouTube is actually
        // playing through -- the same element fullscreen mirrors
        // natively, see VIDEO_SIZE_REPORT_JS's own comment -- and
        // reports its play/pause state, position, title, and artwork
        // back to MediaPlaybackBridge, so MediaPlaybackService's
        // MediaSessionCompat (and therefore the lock screen,
        // notification, and any connected Bluetooth/wired transport
        // controls) stays truthful about what's really happening on
        // the page, not just a mirror of the last command a native
        // control sent it -- tapping YouTube's own on-page pause
        // button has to update the lock screen exactly the same as
        // tapping the lock screen's own pause button would.
        //
        // A MutationObserver re-finds the active <video> on every DOM
        // change (YouTube swaps in a fresh element for autoplay-next
        // rather than reusing one), same pattern as
        // VIDEO_SIZE_REPORT_JS. timeupdate is throttled to roughly
        // once a second -- it fires many times a second natively, and
        // reporting every single tick would mean rebuilding the
        // notification at that same rate for no real benefit: the
        // system already interpolates a smoothly-advancing position
        // between MediaSessionCompat updates using the playback
        // speed, the same way a scrubber on any other media app does.
        // play/pause/seeked/ended report immediately and unthrottled,
        // since those are the transitions actually worth reflecting
        // right away.
        private const val MEDIA_SESSION_JS = """
            (function() {
                if (window.__arktubeMediaSessionInstalled) { return; }
                window.__arktubeMediaSessionInstalled = true;

                var attachedVideo = null;
                var lastReportedTitle = null;
                var lastTimeUpdateReportAt = 0;

                function pageTitle() {
                    // m.youtube.com's <title> is "<video title> -
                    // YouTube" -- strip the suffix so the lock screen/
                    // notification shows just the video's own name,
                    // the way a native player would.
                    var t = document.title || '';
                    var stripped = t.replace(/\s*-\s*YouTube\s*${'$'}/, '');
                    return stripped || t;
                }

                function artworkUrl() {
                    var og = document.querySelector('meta[property="og:image"]');
                    return og ? og.getAttribute('content') : null;
                }

                function reportInfo() {
                    var video = attachedVideo;
                    if (!video || !window.ArkTubeMediaPlayback) { return; }
                    var title = pageTitle();
                    if (title === lastReportedTitle) { return; }
                    lastReportedTitle = title;
                    var durationMs = isFinite(video.duration) ? Math.round(video.duration * 1000) : 0;
                    window.ArkTubeMediaPlayback.onMediaInfo(title, durationMs, artworkUrl());
                }

                function reportState() {
                    var video = attachedVideo;
                    if (!video || !window.ArkTubeMediaPlayback) { return; }
                    window.ArkTubeMediaPlayback.onPlaybackState(
                        !video.paused && !video.ended,
                        Math.round(video.currentTime * 1000),
                        video.playbackRate || 1
                    );
                }

                function reportStateThrottled() {
                    var now = Date.now();
                    if (now - lastTimeUpdateReportAt < 1000) { return; }
                    lastTimeUpdateReportAt = now;
                    reportState();
                }

                function attach(video) {
                    if (!video || video === attachedVideo) { return; }
                    attachedVideo = video;
                    lastReportedTitle = null;
                    ['play', 'pause', 'seeked', 'ended'].forEach(function(evt) {
                        video.addEventListener(evt, reportState);
                    });
                    video.addEventListener('timeupdate', reportStateThrottled);
                    video.addEventListener('loadedmetadata', reportInfo);
                    video.addEventListener('durationchange', reportInfo);
                    reportState();
                    reportInfo();
                }

                function findVideo() {
                    attach(document.querySelector('video'));
                }

                findVideo();
                var observer = new MutationObserver(findVideo);
                observer.observe(document.body, { childList: true, subtree: true });
                document.addEventListener('loadedmetadata', function(e) {
                    if (e.target && e.target.tagName === 'VIDEO') { attach(e.target); }
                }, true);
            })();
        """
    }
}
