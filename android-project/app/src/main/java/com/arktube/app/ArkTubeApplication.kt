package com.arktube.app

import android.app.Application
import com.arktube.app.logging.ArkLogger
import com.arktube.app.notifications.NotificationSyncWorker
import com.arktube.app.notifications.VideoNotificationFactory
import com.google.android.material.color.DynamicColors

/**
 * Enables Material You dynamic color (wallpaper-derived theming) on
 * Android 12+ (API 31+) devices, app-wide, and owns process-wide
 * startup that has to happen before any Activity or background
 * Worker might run -- most importantly [ArkLogger.init], since
 * [NotificationSyncWorker] can execute via WorkManager on its own
 * schedule even if the user never opens MainActivity in a given
 * process lifetime, and its failures still need to reach the
 * on-device failure log.
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
        // Logger first, deliberately outside the track{} below: if
        // init() itself is what's failing, ArkLogger.init() already
        // falls back to Logcat-only on its own (see its own
        // doc comment) -- there's no failure log to report into yet.
        ArkLogger.init(this)

        ArkLogger.track(COMPONENT, "onCreate") {
            DynamicColors.applyToActivitiesIfAvailable(this)
            // See NotificationSyncWorker's own class doc for what this
            // actually does (and why it's a WorkManager poll of the
            // user's existing YouTube login rather than a Data API/OAuth
            // integration). Both calls are idempotent/safe to repeat on
            // every process start.
            VideoNotificationFactory.ensureChannel(this)
            NotificationSyncWorker.schedule(this)
        }
    }

    private companion object {
        const val COMPONENT = "ArkTubeApplication"
    }
}
