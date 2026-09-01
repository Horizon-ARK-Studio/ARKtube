# ARKtube

## Design Document

**Status:** Experimental
**Target:** Android
**Shell:** Native Android (`WebView` + `WebChromeClient`, no bundled assets)
**Initial UI:** YouTube's own mobile web UI (`m.youtube.com`)
**Initial frontend technology:** Existing YouTube frontend / DOM
**Future migration:** Selective native replacement of chrome around the page, not the page itself
**Primary goal:** Make YouTube behave like a proper installed Android app -- on the hardware people actually own, not just this year's flagship -- without redesigning the YouTube experience.

---

## 1. Objective

Build an installable Android application that provides a **1:1 YouTube mobile experience** while behaving like a persistent, native, low-overhead Android app instead of a browser tab.

The application should:

* look and feel like `m.youtube.com`'s existing responsive layout
* retain YouTube's familiar navigation, player, feeds, search, channels, playlists, and recommendations
* run comfortably on low-end and older Android devices, not just recent hardware
* keep the app's process alive and its player state intact across normal navigation, rotation, and backgrounding
* use a plain Android `WebView` as the native application container -- no Chrome Custom Tab hand-off, no separate browser process
* initially avoid rewriting the YouTube UI
* add native OS integration only where the *web* experience structurally can't reach it (fullscreen video compositing, lock-screen/Bluetooth media controls, system bar behavior)
* eventually replace individual pieces of chrome with native components, if and when that's worth the added weight

The guiding principle, unchanged from the desktop line of thinking, is:

> **Do not redesign YouTube. Change the execution model around it.**

On Android specifically, that execution model matters more than it does on desktop, because the audience for it skews toward the devices YouTube's own official app is heaviest on.

---

## 2. Why Android, and Why This Approach, for Low-End Devices

The official YouTube app is a large, feature-dense application: it ships a full recommendation engine's worth of client logic, offline/Premium infrastructure, casting stacks, multiple video pipelines, and a sizeable codebase, all resident in memory together whether or not a given session touches most of it. On a flagship device none of this is noticeable. On a budget device -- 2-3 GB of RAM, an entry-level SoC, a device that's already two or three OS versions behind -- that weight shows up directly as slower cold starts, more aggressive background eviction, and a UI that drops frames the moment the recommendation feed or comments section has to do real work.

The mobile *website*, by contrast, is something YouTube has to keep genuinely lightweight, because it has to work acceptably in a plain browser tab on exactly this same low-end hardware, over exactly this same patchy connection, for people who don't have the app installed at all. `m.youtube.com`'s rendering and JS payload is a fraction of the native app's footprint, and it's continuously kept that way by pressure ARKtube doesn't have to apply itself -- it just has to not get in the way of it.

ARKtube's bet is that wrapping that already-lean web experience in the thinnest possible native shell gets most of what a low-end user actually wants from "the YouTube app" -- an icon on the home screen, it stays logged in, video plays properly, fullscreen works, media keys work -- without paying the official app's memory and CPU tax to get there. Concretely, that means:

* **Lower baseline memory pressure.** One `WebView` instance and a small Kotlin `Activity`/`Service` pair, versus a multi-module native app framework. On a device where the OS is already killing background apps aggressively to keep the foreground one usable, a smaller resident footprint is the difference between ARKtube surviving a quick app-switch and it having to cold-restart every time.
* **Faster cold start.** There's no native UI framework to inflate, no client-side database to open, no large asset bundle to unpack -- `WebView` starts, and the page it loads is the same page YouTube already optimizes for first paint on slow connections and weak CPUs.
* **No local copy of YouTube to maintain, keep in sync, or fall behind on.** Because the "UI" is just YouTube's own live site, ARKtube automatically stays current with YouTube's own mobile-performance work, rather than shipping and having to maintain a bespoke reimplementation that a low-end device would render just as heavily as the real thing.
* **Battery cost proportional to actually playing video, not to running a large always-on app shell.** `MediaPlaybackService` (see Section 9) only promotes itself to a foreground service, and only keeps the screen alive, while a video is genuinely playing -- not for the app's entire lifetime -- which matters more on a smaller battery than it does on a flagship with power to spare.
* **Works on older Android versions for longer.** A thin `WebView`-based shell has a much smaller surface of native-API assumptions than a full native app, so it's cheaper to keep supporting the API levels that older, cheaper, and hand-me-down devices are still running.

None of this makes ARKtube *better* than the official app on capability -- it deliberately does less. The claim is narrower and more specific: for the phone that struggles to keep the real YouTube app comfortable, a native shell around the mobile site is a meaningfully lighter way to get the same core experience -- watch videos, stay logged in, use the fullscreen player, control playback from the lock screen -- without the overhead that experience doesn't strictly need.

---

## 3. Non-Goals

This project is not intended to be:

* a new YouTube frontend
* a privacy-focused YouTube alternative
* a Piped/NewPipe/Invidious-style client
* a visual redesign
* a replacement recommendation algorithm
* an independent video-hosting platform
* offline download support, ad-blocking, or any behavior that changes what YouTube itself is willing to serve
* a full rewrite of YouTube's frontend
* an attempt to reproduce YouTube's proprietary source code or bundle a local copy of its site
* a claim of parity with the official app's feature set -- it is a deliberately smaller thing, aimed at a specific hardware/connectivity profile

The build should contain as little custom UI as possible. Every native line of code should exist because the *web layer structurally cannot* do that thing (see Section 6 below) -- not because native felt nicer to write.

---

## 4. High-Level Architecture

```text
+-------------------------------------------------------------+
|                     Android Application                     |
|                                                               |
|  +-----------------------------------------------------+     |
|  |                     MainActivity                     |     |
|  |                                                       |     |
|  |   rootLayout (FrameLayout, single setContentView)     |     |
|  |   +-------------------------------------------+       |     |
|  |   |                    WebView                 |       |     |
|  |   |                                             |       |     |
|  |   |              m.youtube.com                  |       |     |
|  |   |                                             |       |     |
|  |   |   Home / Search / Watch / Shorts / Channel /|       |     |
|  |   |   Playlist / Subscriptions -- YouTube's own |       |     |
|  |   |   responsive mobile layout, untouched        |       |     |
|  |   +-------------------------------------------+       |     |
|  |              (permanently attached -- see Section 5)  |     |
|  |                                                       |     |
|  |   fullscreenContainer (added only during fullscreen)  |     |
|  |   +-------------------------------------------+       |     |
|  |   |   WebChromeClient.onShowCustomView's video  |       |     |
|  |   |   (hardware-composited SurfaceView, native)  |       |     |
|  |   +-------------------------------------------+       |     |
|  |   stretchToggleButton (top-level sibling, see 6)       |     |
|  +-----------------------------------------------------+     |
|                                                               |
|                     MediaPlaybackService                     |
|              (foreground only while actually playing)        |
+-------------------------------------------------------------+
```

Android/`WebView` owns:

* application lifecycle, process, and window
* installation and the home-screen icon/splash
* fullscreen video compositing (`WebChromeClient.onShowCustomView`/`onHideCustomView`)
* system bar / immersive-mode behavior during fullscreen
* device orientation locking to match the video's own shape
* the lock-screen/notification/Bluetooth media session
* status/nav bar theming to match whatever YouTube itself is rendering

YouTube (the live mobile site, unmodified) owns:

* visual UI, layout, and responsive breakpoints
* the video player itself, playback logic, quality selection
* navigation, search, recommendations
* authentication, account state, cookies/session
* subscriptions, playlists, watch history, channel pages

The client is a **native shell and a small number of native-only affordances layered on top of the site**, not a replacement YouTube implementation. This split is what keeps the native codebase small enough to stay cheap on low-end hardware -- see Section 2.

---

## 5. Core Design Principle

The application maintains a single persistent `WebView`, attached to the window for the entire life of the Activity -- including while fullscreen video is showing.

An earlier prototype instead called `setContentView(customView)` to show fullscreen video, fully detaching the `WebView` from the window while fullscreen was active. That detachment ties directly into the page's own Page Visibility API: Android's `WebView` reports `document.hidden = true` the moment it's detached, regardless of whether the app itself is foregrounded. YouTube's own player treats that exactly like the tab going to the background and reacts by exiting fullscreen again almost immediately -- the "fullscreen blinking and reverting" bug this project hit early on.

The fix is architectural, not a workaround: never detach the `WebView`. Fullscreen video is instead drawn in a second, opaque `FrameLayout` added *on top of* the still-attached `WebView`, so the page never observes a visibility change at all.

```text
Fullscreen requested
        |
WebChromeClient.onShowCustomView() fires
        |
WebView stays attached, now just visually covered
        |
opaque video container added as a sibling on top
        |
native crop / orientation / immersive-mode logic takes over
        |
WebChromeClient.onHideCustomView() fires
        |
container removed, WebView was never actually touched
```

This is the same underlying instinct as the desktop version's "don't tear down the shell on navigation" principle, applied to the one native surface (fullscreen video) that Android hands the app outside the DOM entirely.

---

## 6. Fullscreen Video: The Part That Isn't Just a WebView

Fullscreen playback is the one place this project has to do real native work, and it's worth being explicit about *why*, since it's also where most of the low-end-device-specific bugs have shown up.

When YouTube's HTML5 player enters fullscreen, Chromium doesn't keep rendering a `<video>` element through the normal DOM/CSS pipeline. It hands the app a separate native `View` via `WebChromeClient.onShowCustomView()` -- backed by its own hardware-composited `SurfaceView`, entirely outside the page. Two consequences follow directly from that, both of which matter more on cheaper hardware:

* **No CSS or DOM-level fix can touch it.** Object-fit rules, transforms on the `<video>` element, viewport meta tweaks -- none of it reaches this layer. The zoom-to-fill crop that removes YouTube's default letterbox/pillarbox bars has to be a native `View.scaleX`/`scaleY` operation on the `SurfaceView`'s container instead, driven by the video's own intrinsic pixel size (read once, over a small JS bridge, since that's DOM-only information native code has no other way to observe).
* **That `SurfaceView` composites above the normal View hierarchy by default**, via `setZOrderOnTop`/`setZOrderMediaOverlay` flags Chromium sets for efficient hardware compositing. Any native overlay this app adds -- the manual stretch-to-fill toggle, and anything added after it -- has to have those flags actively neutralized on the video's `SurfaceView`, or it silently sits behind the video, unreachable, no matter what order it was added to the layout in.

This second point is worth calling out specifically because it's a *timing*, not a one-time, problem: the actual `SurfaceView` frequently isn't attached inside the handed-back `View` yet at the moment `onShowCustomView()` fires -- it shows up a frame or more later, once the underlying `Surface` is actually created. A one-shot neutralization pass at fullscreen-entry time can run before that child exists and find nothing to fix. The correct fix re-checks on every layout pass for the duration of fullscreen, catching a late-attached `SurfaceView` as soon as it appears rather than assuming it was there from the start.

None of this is Android-version- or device-tier-specific in principle, but it's exactly the kind of subtle native/Chromium interaction that's easy to under-test on a fast device (where the timing window this race depends on is narrow enough to rarely lose) and then hit reliably on a slower one (where it isn't). Low-end-device testing isn't just about frame rate and memory here -- it changes which race conditions actually show up.

---

## 7. Immersive Mode and Orientation

Fullscreen video goes truly edge-to-edge: status bar, nav bar (gesture pill or 3-button), and the notch/camera cutout are all hidden or drawn under, only while the native fullscreen `View` is showing. `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` lets a user still swipe the bars back temporarily without permanently exiting fullscreen.

Android silently redraws system bars on window-focus churn -- including the brief refocus that happens when YouTube's own in-page settings/quality menu opens. Left unhandled, a reasserted status bar can sit in front of the page for exactly long enough to swallow the tap meant for that menu. The fix is to reassert immersive mode on every focus-regain while fullscreen is active, closing that window before a user gets a chance to tap into it.

Orientation is locked to match the *video's* own intrinsic shape rather than the phone's physical orientation -- landscape uploads get a landscape-locked fullscreen, Shorts/portrait video gets portrait-locked -- the way the official app behaves, restoring whatever orientation preceded fullscreen once it ends.

---

## 8. Persistent WebView, Reflow, and Rotation

Locking the activity's own configuration-change handling keeps a rotation from tearing down and recreating the Activity, which would otherwise reload the `WebView` from scratch -- losing scroll position and player state, and flashing a blank page on a device that's already slower to reload anything.

That keeps the Activity alive across rotation, but it also means nothing else runs automatically on rotation unless it's explicitly hooked. Android resizes the `WebView` correctly on its own, and Chromium's layout engine reflows *visible* content fine -- but content that was already rendered off-screen before the rotation (most visibly further rows of the "Up next" feed) can get stuck at the stale pre-rotation width until manually scrolled into view, because the specific resize-related DOM events YouTube's own JS listens for to decide when to re-measure deferred content don't reliably fire just from the `WebView`'s own size change. The fix dispatches those events synthetically and nudges scroll position by a pixel and back, covering either signal YouTube's code might be listening on.

---

## 9. Media Session and Background Playback

The application exposes the currently playing video to the rest of the OS as a real media session, so play/pause/seek/skip reach it from outside the app: the lock screen, the notification shade, a wired headset's inline remote, Bluetooth AVRCP buttons, a paired watch.

A small JS bridge watches the page's own `<video>` element for play/pause/progress state and title/artwork -- the one piece of this feature that genuinely has to come from JS, since "is this element actually playing" and "what's its title" are DOM-only facts. Everything downstream of that -- the actual media session, the foreground notification, audio focus -- lives natively in `MediaPlaybackService`.

Consistent with Section 2, this service is intentionally cheap when it isn't needed: it stays a bound-but-not-foreground service until real playback is actually reported, and only then promotes itself and posts a notification -- rather than eagerly running foreground for the entire time the app is open, which would cost battery and memory a low-end device can less afford to spend on an app that isn't currently playing anything.

---

## 10. Theming

Status and navigation bar colors track whatever YouTube itself is rendering -- its own light/dark toggle, not the phone's system theme -- read from the page's computed background and applied natively, including flipping the bar icons' own light/dark appearance to stay legible against it.

---

## 11. Explicitly Out of Scope for Now

Consistent with keeping the native codebase small (Sections 2-3): a persistent native nav shell/sidebar, download interception, picture-in-picture, a native playlist/queue, Android Auto browsing, Chromecast, ad-blocking, or any custom UI layered permanently over the page. Each of these is a plausible future native addition, but each one also adds weight this project is deliberately trying not to carry until there's a concrete reason it can't be done in the web layer at all.

---

## 12. Success Criteria

The Android build should be considered working when, on a representative **low-end** device (not just a development flagship):

* the app installs, launches, and reaches a usable YouTube feed noticeably faster than a cold start of the official app
* login/session state persists across app restarts
* the in-page fullscreen button reliably enters and stays in fullscreen (no blink-and-revert)
* fullscreen video fills the screen edge-to-edge, with the manual stretch-to-fill toggle visible and tappable at all times fullscreen is active
* rotation during fullscreen locks to the video's own orientation without breaking layout or losing the crop
* lock-screen and Bluetooth/wired media controls correctly reflect and control actual playback state
* the app does not visibly compete for memory/CPU the way the official app can on the same hardware, under normal browsing-and-watching use

---

## 13. Guiding Principle

> A phone that struggles to keep the official YouTube app smooth for more than a few minutes should still be able to watch YouTube comfortably, in something that looks and feels like a real app rather than a browser tab -- without ARKtube itself becoming the next heavy thing on that device.
