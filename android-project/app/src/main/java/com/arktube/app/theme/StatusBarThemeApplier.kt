package com.arktube.app.theme

import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat
import com.arktube.app.logging.ArkLogger

/**
 * Paints the status/nav bars to match the color THEME_SYNC_JS found
 * YouTube actually rendering (falling back to a plain light/dark
 * swatch if that color couldn't be read), and flips the bar icons'
 * own appearance so they stay legible against it.
 */
class StatusBarThemeApplier(private val window: Window) {

    fun apply(isDark: Boolean, cssBackground: String?) {
        ArkLogger.track(COMPONENT, "apply") {
            val barColor = CssColorParser.parse(cssBackground)
                ?: if (isDark) FALLBACK_DARK_COLOR else FALLBACK_LIGHT_COLOR

            window.statusBarColor = barColor
            window.navigationBarColor = barColor

            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    private companion object {
        const val COMPONENT = "StatusBarThemeApplier"
        val FALLBACK_DARK_COLOR = 0xFF0F0F0F.toInt()
        val FALLBACK_LIGHT_COLOR = 0xFFFFFFFF.toInt()
    }
}
