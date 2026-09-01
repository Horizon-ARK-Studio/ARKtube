package com.arktube.app.prefs

import android.content.Context
import com.arktube.app.logging.ArkLogger

/**
 * Tracks which items from `m.youtube.com/feed/notifications`
 * [com.arktube.app.notifications.NotificationSyncWorker] has already
 * seen, so it only ever posts a native Android notification for
 * something genuinely new since the last check -- not the same inbox
 * item again on every poll.
 *
 * Backed by its own SharedPreferences file (separate from
 * [ForceFillPreference]'s "arktube_prefs", which holds unrelated UI
 * state) since this is written from a background Worker rather than
 * the Activity. Same package and same defensive-read/write shape as
 * [ForceFillPreference] -- SharedPreferences I/O is one of this
 * app's silent-failure boundaries (see docs/Foundational/CODE-STYLE.md
 * Section 3), so every read/write here is wrapped accordingly rather
 * than trusting it to always succeed.
 */
class NotificationSyncStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    /**
     * The video/notification IDs already seen as of the last
     * successful poll, most-recent-first. Empty on a fresh install --
     * see [hasEverSynced] for why that first-run case is handled
     * separately from "nothing new happened" -- and also empty (by
     * design) if the underlying read fails, so a corrupt prefs file
     * degrades to "treat everything as new baseline" rather than
     * crashing the worker.
     */
    fun seenIds(): Set<String> = try {
        prefs.getStringSet(KEY_SEEN_IDS, emptySet()) ?: emptySet()
    } catch (t: Throwable) {
        ArkLogger.e(COMPONENT, "Failed to read seen notification IDs; defaulting to empty", t)
        emptySet()
    }

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
    fun hasEverSynced(): Boolean = try {
        prefs.getBoolean(KEY_HAS_SYNCED, false)
    } catch (t: Throwable) {
        ArkLogger.e(COMPONENT, "Failed to read has-synced flag; defaulting to false", t)
        false
    }

    /**
     * Records the current set of IDs as the new baseline. Capped to
     * [MAX_TRACKED_IDS] (keeping the most recent ones, as ordered by
     * the caller) so this can't grow unbounded across months of
     * polling.
     */
    fun recordSeenIds(idsMostRecentFirst: List<String>) {
        try {
            prefs.edit()
                .putStringSet(KEY_SEEN_IDS, idsMostRecentFirst.take(MAX_TRACKED_IDS).toSet())
                .putBoolean(KEY_HAS_SYNCED, true)
                .apply()
        } catch (t: Throwable) {
            // A failed write here just means the next poll re-derives
            // "new" items against a stale baseline -- worse-case a
            // duplicate notification, not a crash -- so this is a
            // warning, not an error.
            ArkLogger.w(COMPONENT, "Failed to persist seen notification IDs", t)
        } finally {
            ArkLogger.d(COMPONENT, "recordSeenIds: ${idsMostRecentFirst.size} id(s)")
        }
    }

    private companion object {
        const val COMPONENT = "NotificationSyncStore"
        const val PREFS_NAME = "arktube_notification_sync"
        const val KEY_SEEN_IDS = "seen_ids"
        const val KEY_HAS_SYNCED = "has_synced"
        const val MAX_TRACKED_IDS = 100
    }
}
