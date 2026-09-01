package com.arktube.app.webview

/**
 * Every piece of JS this app injects into m.youtube.com, in one
 * place. Moved out of MainActivity verbatim (behavior unchanged) so
 * the Activity doesn't have to be read to find "what does ARKtube
 * actually inject" -- see each constant's own comment for what it
 * does and why it has to be JS rather than native code.
 */
object ArkTubeScripts {

    // Dispatches synthetic resize/orientationchange events so any of
    // YouTube's own listeners for those *specific* DOM events fire,
    // even though the WebView's own resize (a real, correct Chromium
    // layout change) doesn't reliably trigger them the way an actual
    // browser window resize would. The small scroll nudge that's the
    // other half of that fix happens natively (webView.scrollBy), not
    // here, since it needs to move the *Android* View's scroll
    // position, not just fire a page-side event.
    const val FORCE_REFLOW_JS = """
        (function() {
            window.dispatchEvent(new Event('resize'));
            window.dispatchEvent(new Event('orientationchange'));
        })();
    """

    // Reports the fullscreen stream's own intrinsic pixel size
    // (video.videoWidth/videoHeight) to native code (ArkTubeOrientation)
    // whenever it changes. This is *all* this JS does -- the actual
    // zoom-to-fill crop lives entirely in Kotlin, since once YouTube's
    // player goes fullscreen, WebView promotes it out of the DOM onto
    // a separate, hardware-composited native View/SurfaceView that
    // page CSS has no way to reach at all.
    const val VIDEO_SIZE_REPORT_JS = """
        (function() {
            if (window.__arktubeVideoSizeReportInstalled) { return; }
            window.__arktubeVideoSizeReportInstalled = true;

            var lastReportedW = 0;
            var lastReportedH = 0;

            function fullscreenVideo() {
                var el = document.fullscreenElement || document.webkitFullscreenElement;
                if (!el) { return null; }
                return el.tagName === 'VIDEO' ? el : el.querySelector('video');
            }

            function reportSize() {
                var video = fullscreenVideo();
                if (!video) { return; }

                var videoW = video.videoWidth;
                var videoH = video.videoHeight;
                if (!videoW || !videoH) { return; }
                if (videoW === lastReportedW && videoH === lastReportedH) { return; }
                lastReportedW = videoW;
                lastReportedH = videoH;
                if (window.ArkTubeOrientation) {
                    window.ArkTubeOrientation.onFullscreenVideoSize(videoW, videoH);
                }
            }

            var pending = false;
            function scheduleReportSize() {
                if (pending) { return; }
                pending = true;
                requestAnimationFrame(function() {
                    pending = false;
                    reportSize();
                });
            }

            document.addEventListener('fullscreenchange', scheduleReportSize);
            document.addEventListener('webkitfullscreenchange', scheduleReportSize);
            window.addEventListener('resize', scheduleReportSize);
            document.addEventListener('fullscreenchange', function() {
                lastReportedW = 0;
                lastReportedH = 0;
                setTimeout(scheduleReportSize, 300);
                setTimeout(scheduleReportSize, 1000);
            });
            document.addEventListener('loadedmetadata', function(e) {
                if (e.target === fullscreenVideo()) {
                    scheduleReportSize();
                }
            }, true);
        })();
    """

    // Reports YouTube's actual rendered page background back to
    // ThemeBridge, so the status/nav bar can match *YouTube's*
    // light/dark toggle specifically, not the phone's system theme.
    //
    // report() is wired to two triggers: a MutationObserver on
    // <html>/<body> attributes (YouTube's SPA toggles classes on both
    // constantly -- scroll-lock, player-active, ad states, etc.) and
    // a 2000ms setInterval as a backstop for changes the observer
    // might miss. Both call report() far more often than the theme
    // actually changes, which is exactly why -- unlike this same
    // function in an earlier revision -- it now checks
    // lastReportedDark/lastReportedBg before calling the bridge at
    // all: see BUG-0001 in docs/bugs-caught/. Without that guard this
    // was posting an identical onThemeChanged() call to native code
    // roughly every 2 seconds (matching BUG-0001's observed decoder-
    // churn cadence almost exactly) even when nothing about the theme
    // had changed, and StatusBarThemeApplier.apply() unconditionally
    // rewrote window.statusBarColor/navigationBarColor and the
    // WindowInsetsController appearance flags on every single one of
    // those calls -- the same "operation that's only safe once per
    // state transition, instead re-run on every repeated report"
    // shape as the SurfaceView z-order bug and the AUDIOFOCUS_GAIN
    // bug elsewhere in this app (see MediaPlaybackService's
    // updatePlaybackState() doc comment), just not yet given the same
    // dedupe treatment VIDEO_SIZE_REPORT_JS/MEDIA_SESSION_JS's own
    // report functions already have.
    const val THEME_SYNC_JS = """
        (function() {
            if (window.__arktubeThemeSyncInstalled) { return; }
            window.__arktubeThemeSyncInstalled = true;

            var lastReportedDark = null;
            var lastReportedBg = null;

            function readBackground(el) {
                if (!el) { return null; }
                return window.getComputedStyle(el).backgroundColor;
            }

            function isDark(rgbaString) {
                if (!rgbaString) { return null; }
                var nums = rgbaString.match(/[\d.]+/g);
                if (!nums || nums.length < 3) { return null; }
                var alpha = nums.length > 3 ? parseFloat(nums[3]) : 1;
                if (alpha === 0) { return null; }
                var r = parseFloat(nums[0]);
                var g = parseFloat(nums[1]);
                var b = parseFloat(nums[2]);
                var luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                return luminance < 128;
            }

            function report() {
                var bg = readBackground(document.body);
                var dark = isDark(bg);
                if (dark === null) {
                    bg = readBackground(document.documentElement);
                    dark = isDark(bg);
                }
                if (dark === null || !window.ArkTubeTheme) { return; }
                // The dedupe check this function was missing: skip
                // the native call entirely when neither value has
                // actually changed since the last report, regardless
                // of which trigger (interval or mutation) fired.
                if (dark === lastReportedDark && bg === lastReportedBg) { return; }
                lastReportedDark = dark;
                lastReportedBg = bg;
                window.ArkTubeTheme.onThemeChanged(dark, bg);
            }

            report();

            var observer = new MutationObserver(report);
            observer.observe(document.documentElement, { attributes: true });
            if (document.body) {
                observer.observe(document.body, { attributes: true });
            }

            setInterval(report, 2000);
        })();
    """

    // Hides YouTube's own "open app" nag button/banner, since this
    // app already *is* that experience, just wrapped natively.
    const val HIDE_OPEN_APP_JS = """
        (function() {
            if (window.__arktubeHideOpenAppInstalled) { return; }
            window.__arktubeHideOpenAppInstalled = true;

            var STYLE_ID = 'arktube-hide-open-app';
            if (!document.getElementById(STYLE_ID)) {
                var style = document.createElement('style');
                style.id = STYLE_ID;
                style.textContent =
                    '.mobile-topbar-header-open-app-button, ' +
                    'ytm-mealbar-promo-renderer { ' +
                    '  display: none !important; ' +
                    '}';
                document.head.appendChild(style);
            }

            function hideByText() {
                var scopes = document.querySelectorAll(
                    'ytm-mobile-topbar-renderer, header, [class*="topbar" i]'
                );
                scopes.forEach(function(scope) {
                    var candidates = scope.querySelectorAll('button, a, ytd-button-renderer, tp-yt-paper-button');
                    candidates.forEach(function(el) {
                        var text = (el.textContent || '').trim().toLowerCase();
                        if (text === 'open app' || text === 'open in app' || text === 'get the app') {
                            el.style.display = 'none';
                        }
                    });
                });
            }

            hideByText();
            setInterval(hideByText, 1500);
        })();
    """

    const val MEDIA_CONTROL_PLAY_JS = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.play(); }
        })();
    """

    const val MEDIA_CONTROL_PAUSE_JS = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.pause(); }
        })();
    """

    fun mediaControlSeekJs(positionMs: Long): String = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.currentTime = ${positionMs / 1000.0}; }
        })();
    """

    fun mediaControlSkipJs(deltaSeconds: Int): String = """
        (function() {
            var v = document.querySelector('video');
            if (v) { v.currentTime = Math.max(0, v.currentTime + ($deltaSeconds)); }
        })();
    """

    // Watches whichever <video> element YouTube is actually playing
    // through and reports play/pause state, position, title, and
    // artwork back to MediaPlaybackBridge -- see MainActivity's class
    // doc for the full MediaSessionCompat picture this feeds into.
    const val MEDIA_SESSION_JS = """
        (function() {
            if (window.__arktubeMediaSessionInstalled) { return; }
            window.__arktubeMediaSessionInstalled = true;

            var attachedVideo = null;
            var lastReportedTitle = null;
            var lastTimeUpdateReportAt = 0;

            function pageTitle() {
                var t = document.title || '';
                var stripped = t.replace(/\s*-\s*YouTube\s*${'$'}/, '');
                return stripped || t;
            }

            function artworkUrl() {
                var og = document.querySelector('meta[property="og:image"]');
                return og ? og.getAttribute('content') : null;
            }

            function reportInfo() {
                var video = attachedVideo;
                if (!video || !window.ArkTubeMediaPlayback) { return; }
                var title = pageTitle();
                if (title === lastReportedTitle) { return; }
                lastReportedTitle = title;
                var durationMs = isFinite(video.duration) ? Math.round(video.duration * 1000) : 0;
                window.ArkTubeMediaPlayback.onMediaInfo(title, durationMs, artworkUrl());
            }

            function reportState() {
                var video = attachedVideo;
                if (!video || !window.ArkTubeMediaPlayback) { return; }
                window.ArkTubeMediaPlayback.onPlaybackState(
                    !video.paused && !video.ended,
                    Math.round(video.currentTime * 1000),
                    video.playbackRate || 1
                );
            }

            function reportStateThrottled() {
                var now = Date.now();
                if (now - lastTimeUpdateReportAt < 1000) { return; }
                lastTimeUpdateReportAt = now;
                reportState();
            }

            function attach(video) {
                if (!video || video === attachedVideo) { return; }
                attachedVideo = video;
                lastReportedTitle = null;
                ['play', 'pause', 'seeked', 'ended'].forEach(function(evt) {
                    video.addEventListener(evt, reportState);
                });
                video.addEventListener('timeupdate', reportStateThrottled);
                video.addEventListener('loadedmetadata', reportInfo);
                video.addEventListener('durationchange', reportInfo);
                reportState();
                reportInfo();
            }

            function findVideo() {
                attach(document.querySelector('video'));
            }

            findVideo();
            var observer = new MutationObserver(findVideo);
            observer.observe(document.body, { childList: true, subtree: true });
            document.addEventListener('loadedmetadata', function(e) {
                if (e.target && e.target.tagName === 'VIDEO') { attach(e.target); }
            }, true);
        })();
    """
}
