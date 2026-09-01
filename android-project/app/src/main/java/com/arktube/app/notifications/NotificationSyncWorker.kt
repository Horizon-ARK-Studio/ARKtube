package com.arktube.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arktube.app.logging.ArkLogger
import com.arktube.app.prefs.NotificationSyncStore
import java.util.concurrent.TimeUnit

/**
 * Periodically checks the signed-in user's own YouTube notification
 * inbox and mirrors anything new as a native Android notification.
 *
 * This class is deliberately thin -- an orchestrator, not the place
 * where the actual scraping or notification-building logic lives
 * (see docs/Foundational/CODE-STYLE.md Section 1). It just wires
 * together the three collaborators that each own one piece of the
 * job:
 *
 *  - [InboxScraper] -- fetches and parses the inbox
 *  - [NotificationSyncStore] -- remembers what's already been seen
 *  - [VideoNotificationFactory] -- builds/posts the actual notifications
 *
 * Runs via WorkManager rather than a plain timer/foreground loop so
 * it keeps polling on a reasonable schedule even while the app itself
 * isn't open, subject to the same OS battery/Doze constraints as any
 * other background sync job. Because this can run without
 * MainActivity ever having started in the current process,
 * [com.arktube.app.logging.ArkLogger] must already be initialized
 * before this can fire -- see ArkTubeApplication.onCreate, which is
 * why that -- not MainActivity -- owns [ArkLogger.init].
 */
class NotificationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = ArkLogger.track(COMPONENT, "doWork") {
        val store = NotificationSyncStore(applicationContext)
        val scraper = InboxScraper(applicationContext)

        val items = scraper.scrape()
            // Null covers both "timed out" and "the page redirected
            // to sign-in, so there's nothing to read" -- either way,
            // just try again on the next scheduled run rather than
            // treating it as a hard failure worth WorkManager retrying
            // sooner than that.
            ?: return@track Result.success()

        val previouslySeen = store.seenIds()
        val newItems = items.filter { it.id !in previouslySeen }

        if (store.hasEverSynced()) {
            // Newest-first in `items` (DOM/document order of the
            // inbox); cap how many we actually push as individual
            // notifications so a long gap between polls (app unused
            // for a week, etc.) can't fire a dozen notifications at
            // once -- summarize the overflow instead.
            val toNotify = newItems.take(MAX_INDIVIDUAL_NOTIFICATIONS)
            toNotify.forEach { VideoNotificationFactory.postVideoNotification(applicationContext, it) }
            val overflow = newItems.size - toNotify.size
            if (overflow > 0) {
                VideoNotificationFactory.postOverflowNotification(applicationContext, overflow)
            }
        } else {
            ArkLogger.d(COMPONENT, "doWork: first sync, recording baseline without notifying")
        }
        // Either way -- first run or not -- record the current inbox
        // as the new baseline so nothing in it is treated as new
        // again on the next poll.
        store.recordSeenIds(items.map { it.id })

        Result.success()
    }

    companion object {
        private const val COMPONENT = "NotificationSyncWorker"
        private const val WORK_NAME = "arktube_notification_sync"
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 5
        private val POLL_INTERVAL = 30L to TimeUnit.MINUTES

        /**
         * Enqueues the periodic poll if it isn't already scheduled.
         * KEEP (not REPLACE) so re-calling this on every app launch
         * doesn't reset an already-running schedule's next-run timer.
         */
        fun schedule(context: Context) = ArkLogger.track(COMPONENT, "schedule") {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(
                POLL_INTERVAL.first, POLL_INTERVAL.second
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
