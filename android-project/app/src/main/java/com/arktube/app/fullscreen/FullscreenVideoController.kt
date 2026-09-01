package com.arktube.app.fullscreen

import android.app.Activity
import android.content.pm.ActivityInfo
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.arktube.app.logging.ArkLogger
import com.arktube.app.prefs.ForceFillPreference

/**
 * Facade (GoF Facade) that owns everything about fullscreen video:
 *
 *  - hosting Chromium's customView (WebChromeClient.onShowCustomView/
 *    onHideCustomView) inside [rootLayout] as a second child on top of
 *    the WebView, which itself is never detached (keeping it attached
 *    is what avoids YouTube's player seeing `document.hidden = true`
 *    and immediately exiting fullscreen again)
 *  - neutralizing the fullscreen SurfaceView's z-order so the native
 *    stretch-to-fill button can actually paint/receive touches above it
 *  - applying the zoom-to-fill crop via [zoomCropStrategy]
 *  - locking rotation to the video's own intrinsic orientation
 *  - immersive system bars for the duration of fullscreen
 *
 * Constructed once per Activity instance and handed the pieces it
 * needs (the activity for window/resources access, the always-present
 * root container, and the persisted force-fill preference) rather
 * than reaching for globals.
 */
class FullscreenVideoController(
    private val activity: Activity,
    private val rootLayout: FrameLayout,
    private val forceFillPreference: ForceFillPreference,
    private val zoomCropStrategy: ZoomCropStrategy = LetterboxZoomCropStrategy()
) {

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null
    private var stretchToggleButton: android.widget.Button? = null
    private var surfaceViewZOrderNeutralized = false
    private var preFullscreenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var lastVideoWidth = 0
    private var lastVideoHeight = 0

    val isShowing: Boolean get() = customView != null

    fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        ArkLogger.track(COMPONENT, "showCustomView") {
            if (customView != null) {
                callback.onCustomViewHidden()
                return@track
            }
            customView = view
            customViewCallback = callback
            surfaceViewZOrderNeutralized = SurfaceViewZOrderNeutralizer.neutralize(view)

            val container = buildFullscreenContainer(view)
            fullscreenContainer = container
            rootLayout.addView(
                container,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            attachStretchToggleButton()

            preFullscreenOrientation = activity.requestedOrientation
            enterImmersiveFullscreen()
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyZoomCrop()
        }
    }

    fun hideCustomView() {
        ArkLogger.track(COMPONENT, "hideCustomView") {
            fullscreenContainer?.let { rootLayout.removeView(it) }
            fullscreenContainer?.removeAllViews()
            fullscreenContainer = null
            stretchToggleButton?.let { rootLayout.removeView(it) }
            stretchToggleButton = null
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            surfaceViewZOrderNeutralized = false
            lastVideoWidth = 0
            lastVideoHeight = 0
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            exitImmersiveFullscreen()
            activity.requestedOrientation = preFullscreenOrientation
        }
    }

    /** Called by OrientationBridge via MainActivity when VIDEO_SIZE_REPORT_JS reports a new size. */
    fun onFullscreenVideoSize(width: Int, height: Int) {
        ArkLogger.track(COMPONENT, "onFullscreenVideoSize($width,$height)") {
            lastVideoWidth = width
            lastVideoHeight = height
            applyOrientationLock(width, height)
            applyZoomCrop()
        }
    }

    /** Reasserts immersive mode on window-focus regain -- see the original class doc for why. */
    fun onWindowFocusRegained() {
        if (isShowing) enterImmersiveFullscreen()
    }

    fun toggleForceFill() {
        ArkLogger.track(COMPONENT, "toggleForceFill") {
            val enabled = forceFillPreference.toggle()
            applyZoomCrop()
            stretchToggleButton?.let { StretchToggleButtonFactory.applyAppearance(it, enabled) }
        }
    }

    private fun buildFullscreenContainer(video: View): FrameLayout {
        val container = FrameLayout(activity)
        container.addView(
            video,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        container.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                if (!surfaceViewZOrderNeutralized) {
                    surfaceViewZOrderNeutralized = SurfaceViewZOrderNeutralizer.neutralize(container)
                }
                applyZoomCrop()
            } catch (t: Throwable) {
                ArkLogger.e(COMPONENT, "Global layout listener failed", t)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { _, insets ->
            try {
                applyZoomCrop()
            } catch (t: Throwable) {
                ArkLogger.e(COMPONENT, "Insets listener failed", t)
            }
            insets
        }
        return container
    }

    private fun attachStretchToggleButton() {
        val button = StretchToggleButtonFactory.create(activity, forceFillPreference.isEnabled) { toggleForceFill() }
        stretchToggleButton = button
        val buttonSizePx = dpToPx(STRETCH_BUTTON_SIZE_DP)
        val marginPx = dpToPx(STRETCH_BUTTON_MARGIN_DP)
        val buttonParams = FrameLayout.LayoutParams(buttonSizePx, buttonSizePx).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            setMargins(marginPx, marginPx, marginPx, marginPx)
        }
        // Added directly to rootLayout, not nested in fullscreenContainer --
        // see SurfaceViewZOrderNeutralizer's doc for why sibling order
        // inside a container wouldn't be enough on its own.
        rootLayout.addView(button, buttonParams)
    }

    private fun applyZoomCrop() {
        val view = customView ?: return
        val containerW = view.width
        val containerH = view.height
        val result = zoomCropStrategy.compute(containerW, containerH, lastVideoWidth, lastVideoHeight, forceFillPreference.isEnabled)
        if (!result.shouldApply) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.pivotX = containerW / 2f
        view.pivotY = containerH / 2f
        view.scaleX = result.scale
        view.scaleY = result.scale
    }

    private fun applyOrientationLock(videoWidth: Int, videoHeight: Int) {
        if (!isShowing || videoWidth <= 0 || videoHeight <= 0) return
        activity.requestedOrientation = if (videoWidth >= videoHeight) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitImmersiveFullscreen() {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), activity.resources.displayMetrics
    ).toInt()

    private companion object {
        const val COMPONENT = "FullscreenVideoController"
        const val STRETCH_BUTTON_SIZE_DP = 40
        const val STRETCH_BUTTON_MARGIN_DP = 16
    }
}
