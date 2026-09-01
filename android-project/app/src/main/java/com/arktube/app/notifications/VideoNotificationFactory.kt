package com.arktube.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.arktube.app.MainActivity
import com.arktube.app.R
import com.arktube.app.logging.ArkLogger

/**
 * GoF Factory: builds and posts the two notification shapes
 * [NotificationSyncWorker] needs -- one per new video/Short, and a
 * single overflow summary when there are more new items in a poll
 * than are worth notifying about individually. Also owns the
 * notification channel those posts go through, since the channel
 * definition and what gets posted into it are the same concern.
 *
 * Mirrors [com.arktube.app.fullscreen.StretchToggleButtonFactory]'s
 * shape: callers never build a `NotificationCompat.Builder` or
 * `PendingIntent` by hand, they just describe *what* happened
 * (a new video, an overflow count) and this decides how it's
 * represented.
 */
object VideoNotificationFactory {

    private const val COMPONENT = "VideoNotificationFactory"
    private const val CHANNEL_ID = "arktube_new_uploads"
    private const val OVERFLOW_NOTIFICATION_ID = 2001

    /**
     * Creates the (separate from MediaPlaybackService's playback
     * channel) notification channel these posts go through. Safe to
     * call unconditionally/repeatedly -- channel creation is
     * idempotent.
     */
    fun ensureChannel(context: Context) = ArkLogger.track(COMPONENT, "ensureChannel") {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return@track
        val channel = NotificationChannel(
            CHANNEL_ID, "New videos & activity", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "New uploads and other notifications from your YouTube subscriptions"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    fun postVideoNotification(context: Context, item: InboxItem) {
        ArkLogger.track(COMPONENT, "postVideoNotification(${item.id})") {
            if (!hasPermission(context)) {
                ArkLogger.d(COMPONENT, "postVideoNotification: no POST_NOTIFICATIONS permission, skipping")
                return@track
            }
            val contentIntent = PendingIntent.getActivity(
                context, item.id.hashCode(),
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(MainActivity.EXTRA_OPEN_VIDEO_URL, item.url)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(item.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), notification)
        }
    }

    fun postOverflowNotification(context: Context, overflowCount: Int) {
        ArkLogger.track(COMPONENT, "postOverflowNotification($overflowCount)") {
            if (!hasPermission(context)) {
                ArkLogger.d(COMPONENT, "postOverflowNotification: no POST_NOTIFICATIONS permission, skipping")
                return@track
            }
            val contentIntent = PendingIntent.getActivity(
                context, OVERFLOW_NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(MainActivity.EXTRA_OPEN_VIDEO_URL, InboxScraper.NOTIFICATIONS_URL)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText("$overflowCount more new notifications")
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            NotificationManagerCompat.from(context).notify(OVERFLOW_NOTIFICATION_ID, notification)
        }
    }

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
}
