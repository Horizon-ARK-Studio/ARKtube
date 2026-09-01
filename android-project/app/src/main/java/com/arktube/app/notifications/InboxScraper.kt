package com.arktube.app.notifications

import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import com.arktube.app.logging.ArkLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import kotlin.coroutines.resume

/** One `m.youtube.com/feed/notifications` inbox entry, as scraped from the DOM. */
data class InboxItem(val id: String, val title: String, val url: String)

/**
 * Loads `m.youtube.com/feed/notifications` in a short-lived, headless
 * WebView and scrapes it into a list of [InboxItem]. Split out of
 * [NotificationSyncWorker] so the worker itself stays a thin
 * orchestrator (see docs/Foundational/CODE-STYLE.md Section 1) --
 * this class owns exactly one reason to change: how the inbox is
 * fetched and parsed, independent of scheduling or notification
 * posting.
 *
 * Deliberately does NOT use the YouTube Data API/OAuth for this. That
 * would mean a second, separate Google sign-in inside the app -- on
 * top of, and possibly for a different account than, whatever the
 * user already logs into inside MainActivity's WebView -- just to
 * learn about the same subscriptions YouTube's own page already knows
 * about for that WebView session. Instead, this spins up its own
 * headless WebView, points it at the *same* inbox page, and lets it
 * inherit the exact same login: Android's CookieManager is shared and
 * persisted across every WebView instance in the app (not just
 * MainActivity's), so whatever account the user is logged into
 * m.youtube.com as there is automatically the account this reads
 * notifications for too. No API key, no OAuth consent screen, nothing
 * to separately sign into.
 *
 * The tradeoff, in keeping with the rest of this app's approach to
 * YouTube (see ArkTubeScripts' HIDE_OPEN_APP_JS/MEDIA_SESSION_JS):
 * this reads the page's live DOM rather than a stable API contract,
 * so [NOTIFICATION_SCRAPE_JS] is written to be structurally tolerant
 * (any link to a video, wherever it sits in the inbox markup) rather
 * than depending on exact, easily-changed class names -- but it can
 * still need updating if YouTube's markup changes enough to break it.
 */
class InboxScraper(private val context: Context) {

    /**
     * Returns null if the poll timed out, the page redirected to a
     * sign-in flow (the user isn't logged into YouTube at all, so
     * there's nothing to read), or the scrape otherwise came back
     * empty/unparseable -- any of these is treated as "try again next
     * scheduled run" by the caller, not a hard failure.
     */
    suspend fun scrape(): List<InboxItem>? = ArkLogger.track(COMPONENT, "scrape") {
        withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { loadAndScrapeInbox() }
    }

    private suspend fun loadAndScrapeInbox(): List<InboxItem>? = withContext(Dispatchers.Main) {
        val webView = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            // Same mobile-UA trick as ArkTubeWebViewFactory, for the
            // same reason: get m.youtube.com's real phone
            // layout/markup, not a desktop layout squeezed into a
            // WebView-sized box.
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
                ArkLogger.d(COMPONENT, "loadAndScrapeInbox: no result (null load or sign-in redirect)")
                return@withContext null
            }
            val rawJson = awaitJsResult(webView, NOTIFICATION_SCRAPE_JS)
            parseInboxJson(rawJson)
        } catch (t: Throwable) {
            ArkLogger.e(COMPONENT, "loadAndScrapeInbox failed", t)
            null
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
        try {
            webView.stopLoading()
            webView.webViewClient = object : WebViewClient() {}
            webView.loadUrl("about:blank")
            webView.clearHistory()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (t: Throwable) {
            // Best-effort teardown -- a leaked headless WebView is a
            // resource leak worth knowing about, but shouldn't take
            // the worker down with it.
            ArkLogger.w(COMPONENT, "destroyHeadlessWebView failed", t)
        }
    }

    /**
     * `rawJson` is `evaluateJavascript`'s result: a JSON *string*
     * (quoted and escaped) containing [NOTIFICATION_SCRAPE_JS]'s own
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
        } catch (t: Throwable) {
            ArkLogger.w(COMPONENT, "parseInboxJson: malformed scrape result", t)
            null
        }
    }

    companion object {
        /** Public so [VideoNotificationFactory]'s overflow notification can deep-link back to the inbox itself. */
        const val NOTIFICATIONS_URL = "https://m.youtube.com/feed/notifications"

        private const val COMPONENT = "InboxScraper"
        private const val SCRAPE_TIMEOUT_MS = 20_000L
        const val HEADLESS_WIDTH_PX = 1080
        const val HEADLESS_HEIGHT_PX = 1920

        /**
         * Scrapes the inbox generically -- any link to a video or
         * Short, wherever it sits in the page's markup -- rather than
         * depending on exact, frequently-changed class names (same
         * "match by structure/text, not brittle selectors" approach
         * this app's other page-JS already takes elsewhere, e.g.
         * ArkTubeScripts' HIDE_OPEN_APP_JS). Returns a JSON-encoded
         * array of `{id, title, url}` (newest/topmost first, deduped
         * by video ID), or `null` if nothing was found.
         */
        const val NOTIFICATION_SCRAPE_JS = """
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
    }
}
