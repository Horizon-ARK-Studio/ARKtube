package com.arktube.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arktube.app.fullscreen.FullscreenVideoController
import com.arktube.app.layout.LayoutReflowHelper
import com.arktube.app.logging.ArkLogger
import com.arktube.app.media.MediaSessionCoordinator
import com.arktube.app.prefs.ForceFillPreference
import com.arktube.app.theme.StatusBarThemeApplier
import com.arktube.app.webview.ArkTubeScripts
import com.arktube.app.webview.ArkTubeWebViewFactory

/**
 * Stage 0 scaffold for the ARKtube Android edition.
 *
 * This mirrors the desktop app's model exactly (see
 * ../../../docs/PROBLEM-STATEMENT.md at the repo root): don't
 * redesign YouTube, don't bundle a copy of it -- just point a WebView
 * at the real, live site and let YouTube be YouTube.
 *
 * As of this refactor, MainActivity itself is deliberately thin: it
 * owns the Activity lifecycle and wires together a handful of
 * single-responsibility collaborators, each in its own package under
 * com.arktube.app --
 *
 *  - `webview.ArkTubeWebViewFactory` -- builds/configures the WebView
 *    and its three JS bridges (GoF Factory)
 *  - `webview.bridge.*` -- the JS bridges themselves, sharing a common
 *    `ArkTubeJsBridge` abstract base for uniform try/catch/finally
 *    logging (GoF Abstract Class / Template Method)
 *  - `fullscreen.FullscreenVideoController` -- everything about
 *    fullscreen video: customView hosting, the SurfaceView z-order
 *    fix, the zoom-to-fill crop, immersive bars, orientation lock
 *    (GoF Facade over that whole subsystem)
 *  - `fullscreen.ZoomCropStrategy` -- the crop math itself, swappable
 *    independent of the controller (GoF Strategy)
 *  - `fullscreen.StretchToggleButtonFactory` -- builds the
 *    stretch-to-fill button (GoF Factory)
 *  - `media.MediaSessionCoordinator` -- MediaPlaybackService binding
 *    and JS transport-control dispatch
 *  - `theme.StatusBarThemeApplier` / `theme.CssColorParser` -- status
 *    bar theme sync
 *  - `layout.LayoutReflowHelper` -- the post-rotation reflow workaround
 *  - `prefs.ForceFillPreference` -- persisted stretch-to-fill state
 *  - `logging.ArkLogger` -- app-wide logger (GoF Singleton); mirrors
 *    every warning/error to an on-device file
 *    (`filesDir/--log-failed`) in addition to Logcat, so a failure can
 *    be pulled off a device after the fact even without a live
 *    debugger attached
 *
 * All the *why* behind each individual behavior (edge-to-edge
 * fullscreen, the zoom crop, MediaSessionCompat integration, theme
 * sync, the "open app" nag removal, etc.) now lives as doc comments on
 * the collaborator that actually implements it, rather than one large
 * comment block here -- see each class listed above.
 *
 * Explicitly out of scope even with the above (future stages, see the
 * repo-root roadmap): a persistent nav shell/sidebar, download
 * interception, PiP, a real playlist/queue or Android Auto browsing,
 * chromecast, ad-blocking, or any custom UI layered over the page.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var fullscreenController: FullscreenVideoController
    private lateinit var mediaSessionCoordinator: MediaSessionCoordinator
    private lateinit var statusBarThemeApplier: StatusBarThemeApplier
    private lateinit var layoutReflowHelper: LayoutReflowHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate().
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // ArkLogger.init() is called once from ArkTubeApplication.onCreate
        // instead of here -- it needs to be live before
        // NotificationSyncWorker can possibly run, which can happen
        // via WorkManager without MainActivity ever starting.

        ArkLogger.track(COMPONENT, "onCreate") {
            configureWindowForCutout()

            val forceFillPreference = ForceFillPreference(this)
            statusBarThemeApplier = StatusBarThemeApplier(window)

            rootLayout = FrameLayout(this)
            setContentView(rootLayout)

            // The exit-fullscreen callback references layoutReflowHelper
            // lazily (same pattern as mediaSessionCoordinator's { webView }
            // provider just below) -- it's a lateinit var not yet assigned
            // at this point in onCreate(), but the lambda itself only runs
            // later, from hideCustomView(), by which point onCreate() has
            // finished and layoutReflowHelper definitely exists.
            fullscreenController = FullscreenVideoController(
                activity = this,
                rootLayout = rootLayout,
                forceFillPreference = forceFillPreference,
                onExitFullscreen = { layoutReflowHelper.reflow { fullscreenController.isShowing } }
            )
            // Safe to construct before webView exists: the { webView }
            // provider lambda only reads the lateinit property lazily,
            // the first time a transport command actually needs it.
            mediaSessionCoordinator = MediaSessionCoordinator(this) { webView }

            webView = ArkTubeWebViewFactory.create(
                context = this,
                themeListener = { isDark, cssBackground ->
                    runOnUiThread {
                        ArkLogger.track(COMPONENT, "themeListener") {
                            statusBarThemeApplier.apply(isDark, cssBackground)
                        }
                    }
                },
                orientationListener = { width, height ->
                    runOnUiThread { fullscreenController.onFullscreenVideoSize(width, height) }
                },
                mediaPlaybackListener = mediaSessionCoordinator,
                webChromeClient = buildWebChromeClient()
            )
            // webView is added to rootLayout here and never removed or
            // detached again for the rest of the Activity's life -- see
            // FullscreenVideoController's class doc for why keeping it
            // permanently attached (just visually covered during
            // fullscreen) matters for YouTube's Page Visibility handling.
            rootLayout.addView(
                webView,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )

            layoutReflowHelper = LayoutReflowHelper(webView)

            // If this launch came from tapping a NotificationSyncWorker
            // notification, EXTRA_OPEN_VIDEO_URL carries the specific
            // video (or the inbox page itself) it should open --
            // otherwise just load the normal home feed.
            webView.loadUrl(intent?.getStringExtra(EXTRA_OPEN_VIDEO_URL) ?: ArkTubeWebViewFactory.SITE_URL)

            // Only a *bound* (not yet foreground) service at this point --
            // binding early just gets the command listener wired up
            // before the user could possibly reach a play button.
            mediaSessionCoordinator.bind()

            requestNotificationPermissionIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ArkLogger.track(COMPONENT, "onDestroy") {
            mediaSessionCoordinator.unbind()
        }
    }

    /**
     * AndroidManifest.xml declares MainActivity `launchMode="singleTask"`
     * specifically so tapping a NotificationSyncWorker notification
     * while the app is already running routes here instead of onCreate()
     * building a second instance on top of it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ArkLogger.track(COMPONENT, "onNewIntent") {
            setIntent(intent)
            intent.getStringExtra(EXTRA_OPEN_VIDEO_URL)?.let { url ->
                if (fullscreenController.isShowing) {
                    fullscreenController.hideCustomView()
                }
                webView.loadUrl(url)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        ArkLogger.track(COMPONENT, "onBackPressed") {
            when {
                fullscreenController.isShowing -> fullscreenController.hideCustomView()
                webView.canGoBack() -> webView.goBack()
                else -> super.onBackPressed()
            }
        }
    }

    /**
     * AndroidManifest.xml declares
     * `android:configChanges="orientation|screenSize|keyboardHidden"`
     * so a rotation doesn't tear down/recreate this Activity -- which
     * also means the off-screen-content reflow workaround has to be
     * hooked here explicitly. See LayoutReflowHelper's own doc for why
     * it's needed at all.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ArkLogger.track(COMPONENT, "onConfigurationChanged") {
            layoutReflowHelper.reflow { fullscreenController.isShowing }
        }
    }

    /**
     * Reasserts immersive fullscreen on window-focus regain -- see
     * FullscreenVideoController.onWindowFocusRegained()'s doc for why
     * (Android silently redraws system bars on any focus churn,
     * including the brief refocus YouTube's own settings menu causes).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ArkLogger.track(COMPONENT, "onWindowFocusChanged") {
                fullscreenController.onWindowFocusRegained()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification permission is only needed to actually *show* the
        // media notification (Android 13+) -- MediaSessionCompat itself
        // works regardless of whether this is granted, so there's
        // nothing else gated on the result; this override exists purely
        // so the outcome is logged for observability.
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            ArkLogger.i(COMPONENT, "POST_NOTIFICATIONS permission result: granted=$granted")
        }
    }

    /**
     * Fullscreen video support: YouTube's player swaps in a custom
     * fullscreen view via these callbacks. Without handling them, the
     * in-page fullscreen button is a dead click. All the actual work
     * is delegated to FullscreenVideoController.
     */
    private fun buildWebChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
            ArkLogger.track(COMPONENT, "onShowCustomView") {
                webView.evaluateJavascript(ArkTubeScripts.VIDEO_SIZE_REPORT_JS, null)
                fullscreenController.showCustomView(view, callback)
            }
        }

        override fun onHideCustomView() {
            ArkLogger.track(COMPONENT, "onHideCustomView") {
                fullscreenController.hideCustomView()
            }
        }
    }

    /**
     * Lets fullscreen video draw under the notch/camera cutout instead
     * of YouTube's custom view being letterboxed around it. Must be set
     * on the window's LayoutParams directly (not just the insets
     * controller) or the cutout area stays reserved regardless of what
     * onShowCustomView does later.
     */
    private fun configureWindowForCutout() {
        ArkLogger.track(COMPONENT, "configureWindowForCutout") {
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
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        ArkLogger.track(COMPONENT, "requestNotificationPermissionIfNeeded") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    companion object {
        // Read by both onCreate() (cold start from a notification tap)
        // and onNewIntent() (tapped while already running); set by
        // NotificationSyncWorker. Must stay public/non-private -- it's
        // referenced from outside this class.
        const val EXTRA_OPEN_VIDEO_URL = "arktube_open_video_url"

        private const val COMPONENT = "MainActivity"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    }
}
