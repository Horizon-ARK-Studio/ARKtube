package com.arktube.app

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Enables Material You dynamic color (wallpaper-derived theming) on
 * Android 12+ (API 31+) devices, app-wide.
 *
 * [DynamicColors.applyToActivitiesIfAvailable] is a no-op below API
 * 31, so this is safe across this app's full minSdk range -- devices
 * that can't do dynamic color just keep the branded fallback palette
 * defined in themes.xml / values-night/themes.xml. In practice this
 * only affects the splash background and system bars, since the
 * WebView content itself is youtube.com's own theming, not this
 * app's.
 */
class ArkTubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        // See NotificationSyncWorker's own class doc for what this
        // actually does (and why it's a WorkManager poll of the
        // user's existing YouTube login rather than a Data API/OAuth
        // integration). Both calls are idempotent/safe to repeat on
        // every process start.
        NotificationSyncWorker.ensureNotificationChannel(this)
        NotificationSyncWorker.schedule(this)
    }
}
