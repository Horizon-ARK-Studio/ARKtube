package com.arktube.app

import android.content.Context

/**
 * Tracks which items from `m.youtube.com/feed/notifications`
 * NotificationSyncWorker has already seen, so it only ever posts a
 * native Android notification for something genuinely new since the
 * last check -- not the same inbox item again on every poll.
 *
 * Backed by its own SharedPreferences file (separate from
 * MainActivity's "arktube_prefs", which holds unrelated UI state)
 * since this is written from a background Worker rather than the
 * Activity.
 */
class NotificationSyncStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    /**
     * The video/notification IDs already seen as of the last
     * successful poll, most-recent-first. Empty on a fresh install --
     * see [hasEverSynced] for why that first-run case is handled
     * separately from "nothing new happened".
     */
    fun seenIds(): Set<String> = prefs.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet()

    /**
     * Whether NotificationSyncWorker has completed at least one
     * successful poll before. On the very first run there's no
     * baseline to diff against, so every item in the inbox would
     * otherwise look "new" and flood the user with a notification for
     * their entire existing notification history the moment they
     * install the app. The first run instead just records the
     * current baseline silently; only runs after that ever post
     * notifications.
     */
    fun hasEverSynced(): Boolean = prefs.getBoolean(KEY_HAS_SYNCED, false)

    /**
     * Records the current set of IDs as the new baseline. Capped to
     * [MAX_TRACKED_IDS] (keeping the most recent ones, as ordered by
     * the caller) so this can't grow unbounded across months of
     * polling.
     */
    fun recordSeenIds(idsMostRecentFirst: List<String>) {
        prefs.edit()
            .putStringSet(KEY_SEEN_IDS, idsMostRecentFirst.take(MAX_TRACKED_IDS).toSet())
            .putBoolean(KEY_HAS_SYNCED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "arktube_notification_sync"
        private const val KEY_SEEN_IDS = "seen_ids"
        private const val KEY_HAS_SYNCED = "has_synced"
        private const val MAX_TRACKED_IDS = 100
    }
}
