package com.arktube.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Periodically checks the signed-in user's own YouTube notification
 * inbox (`m.youtube.com/feed/notifications` -- the page behind the
 * bell icon: new uploads from subscriptions, replies, etc.) and
 * mirrors anything new as a native Android notification.
 *
 * Deliberately does NOT use the YouTube Data API/OAuth for this. That
 * would mean a second, separate Google sign-in inside the app --
 * on top of, and possibly for a different account than, whatever the
 * user already logs into inside MainActivity's WebView -- just to
 * learn about the same subscriptions YouTube's own page already
 * knows about for that WebView session. Instead, this spins up its
 * own short-lived, invisible WebView, points it at the *same* inbox
 * page, and lets it inherit the exact same login: Android's
 * CookieManager is shared and persisted across every WebView instance
 * in the app (not just MainActivity's), so whatever account the user
 * is logged into m.youtube.com as there is automatically the account
 * this reads notifications for too. No API key, no OAuth consent
 * screen, nothing to separately sign into.
 *
 * The tradeoff, in keeping with the rest of this app's approach to
 * YouTube (see HIDE_OPEN_APP_JS/MEDIA_SESSION_JS in MainActivity):
 * this reads the page's live DOM rather than a stable API contract,
 * so NOTIFICATION_SCRAPE_JS is written to be structurally tolerant
 * (any link to a video, wherever it sits in the inbox markup) rather
 * than depending on exact, easily-changed class names -- but it can
 * still need updating if YouTube's markup changes enough to break it.
 *
 * Runs via WorkManager rather than a plain timer/foreground loop so
 * it keeps polling on a reasonable schedule even while the app itself
 * isn't open, subject to the same OS battery/Doze constraints as any
 * other background sync job.
 */
class NotificationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private data class InboxItem(val id: String, val title: String, val url: String)

    override suspend fun doWork(): Result {
        val store = NotificationSyncStore(applicationContext)
        val items = withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { scrapeInbox() }
            // Null covers both "timed out" and "the page redirected
            // to sign-in, so there's nothing to read" -- either way,
            // just try again on the next scheduled run rather than
            // treating it as a hard failure worth WorkManager retrying
            // sooner than that.
            ?: return Result.success()

        val previouslySeen = store.seenIds()
        val newItems = items.filter { it.id !in previouslySeen }

        if (store.hasEverSynced()) {
            // Newest-first in `items` (DOM/document order of the
            // inbox); cap how many we actually push as individual
            // notifications so a long gap between polls (app unused
            // for a week, etc.) can't fire a dozen notifications at
            // once -- summarize the overflow instead.
            val toNotify = newItems.take(MAX_INDIVIDUAL_NOTIFICATIONS)
            toNotify.forEach { postVideoNotification(it) }
            val overflow = newItems.size - toNotify.size
            if (overflow > 0) {
                postOverflowNotification(overflow)
            }
        }
        // Either way -- first run or not -- record the current inbox
        // as the new baseline so nothing in it is treated as new
        // again on the next poll.
        store.recordSeenIds(items.map { it.id })

        return Result.success()
    }

    /**
     * Loads the notifications inbox in a headless WebView (created
     * and torn down entirely within this call, on the main thread --
     * WebView is not usable off it) and scrapes it via
     * NOTIFICATION_SCRAPE_JS.
     *
     * Returns null if the page redirected to a sign-in flow (the user
     * isn't logged into YouTube at all, so there's nothing to poll)
     * or the scrape otherwise came back empty/unparseable.
     */
    private suspend fun scrapeInbox(): List<InboxItem>? = withContext(Dispatchers.Main) {
        val webView = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Same mobile-UA trick as MainActivity, for the same
            // reason: get m.youtube.com's real phone layout/markup,
            // not a desktop layout squeezed into a WebView-sized box.
            settings.userAgentString = settings.userAgentString?.replace("; wv", "")
            // Never attached to a window (this WebView has no visual
            // presence at all), but Chromium still wants a real
            // measured/laid-out size to render and run page JS
            // reliably against -- an arbitrary phone-sized box is
            // enough; nothing here is ever actually displayed.
            layout(0, 0, HEADLESS_WIDTH_PX, HEADLESS_HEIGHT_PX)
        }

        try {
            val finalUrl = awaitPageLoad(webView, NOTIFICATIONS_URL)
            if (finalUrl == null || isSignInUrl(finalUrl)) {
                return@withContext null
            }
            val rawJson = awaitJsResult(webView, NOTIFICATION_SCRAPE_JS)
            parseInboxJson(rawJson)
        } finally {
            destroyHeadlessWebView(webView)
        }
    }

    private fun isSignInUrl(url: String): Boolean =
        url.contains("accounts.google.com") || url.contains("ServiceLogin")

    /** Suspends until `webView` finishes loading `url`, resuming with the page's final URL (post-redirects). */
    private suspend fun awaitPageLoad(webView: WebView, url: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String?) {
                    if (continuation.isActive) {
                        continuation.resume(finishedUrl)
                    }
                }
            }
            webView.loadUrl(url)
        }

    /** Suspends until `script` finishes evaluating, resuming with its raw (still JSON-encoded-as-a-string) result. */
    private suspend fun awaitJsResult(webView: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

    private fun destroyHeadlessWebView(webView: WebView) {
        webView.stopLoading()
        webView.webViewClient = object : WebViewClient() {}
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /**
     * `rawJson` is `evaluateJavascript`'s result: a JSON *string*
     * (quoted and escaped) containing NOTIFICATION_SCRAPE_JS's own
     * JSON-encoded array, or the literal string "null" if the script
     * threw/found nothing. Unwrap the outer encoding first, then
     * parse the actual array.
     */
    private fun parseInboxJson(rawJson: String?): List<InboxItem>? {
        if (rawJson.isNullOrBlank() || rawJson == "null") return null
        return try {
            val unwrapped = org.json.JSONTokener(rawJson).nextValue() as String
            val array = JSONArray(unwrapped)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                InboxItem(id, title, url)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun postVideoNotification(item: InboxItem) {
        if (!hasNotificationPermission()) return
        val contentIntent = PendingIntent.getActivity(
            applicationContext, item.id.hashCode(),
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_OPEN_VIDEO_URL, item.url)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(item.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(item.id.hashCode(), notification)
    }

    private fun postOverflowNotification(overflowCount: Int) {
        if (!hasNotificationPermission()) return
        val contentIntent = PendingIntent.getActivity(
            applicationContext, OVERFLOW_NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra(MainActivity.EXTRA_OPEN_VIDEO_URL, NOTIFICATIONS_URL)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("$overflowCount more new notifications")
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(OVERFLOW_NOTIFICATION_ID, notification)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val NOTIFICATIONS_URL = "https://m.youtube.com/feed/notifications"
        private const val CHANNEL_ID = "arktube_new_uploads"
        private const val WORK_NAME = "arktube_notification_sync"
        private const val OVERFLOW_NOTIFICATION_ID = 2001
        private const val MAX_INDIVIDUAL_NOTIFICATIONS = 5
        private const val SCRAPE_TIMEOUT_MS = 20_000L
        private const val HEADLESS_WIDTH_PX = 1080
        private const val HEADLESS_HEIGHT_PX = 1920
        private val POLL_INTERVAL = 30L to TimeUnit.MINUTES

        /**
         * Scrapes the inbox generically -- any link to a video or
         * Short, wherever it sits in the page's markup -- rather than
         * depending on exact, frequently-changed class names (same
         * "match by structure/text, not brittle selectors" approach
         * MainActivity's own YouTube-page JS already takes elsewhere,
         * e.g. HIDE_OPEN_APP_JS). Returns a JSON-encoded array of
         * `{id, title, url}` (newest/topmost first, deduped by video
         * ID), or `null` if nothing was found.
         */
        private const val NOTIFICATION_SCRAPE_JS = """
            (function() {
                try {
                    var seen = {};
                    var results = [];
                    var anchors = document.querySelectorAll('a[href]');
                    for (var i = 0; i < anchors.length && results.length < 25; i++) {
                        var a = anchors[i];
                        var href = a.getAttribute('href') || '';
                        var match = href.match(/[?&]v=([a-zA-Z0-9_-]{6,})/) ||
                            href.match(/\/shorts\/([a-zA-Z0-9_-]{6,})/);
                        if (!match) { continue; }
                        var id = match[1];
                        if (seen[id]) { continue; }
                        var label = (a.getAttribute('aria-label') || a.textContent || a.getAttribute('title') || '').trim();
                        if (!label) { continue; }
                        seen[id] = true;
                        var url = href.indexOf('http') === 0 ? href : ('https://m.youtube.com' + href);
                        results.push({ id: id, title: label, url: url });
                    }
                    return results.length ? JSON.stringify(results) : null;
                } catch (e) {
                    return null;
                }
            })();
        """

        /**
         * Creates the (separate from MediaPlaybackService's playback
         * channel) notification channel this worker posts through.
         * Safe to call unconditionally/repeatedly -- channel creation
         * is idempotent.
         */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID, "New videos & activity", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New uploads and other notifications from your YouTube subscriptions"
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        /**
         * Enqueues the periodic poll if it isn't already scheduled.
         * KEEP (not REPLACE) so re-calling this on every app launch
         * doesn't reset an already-running schedule's next-run timer.
         */
        fun schedule(context: Context) {
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
