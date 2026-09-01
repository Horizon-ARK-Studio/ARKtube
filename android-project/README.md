# ARKtube — Android edition (Stage 0)

Stage 0 scaffold for an Android build of ARKtube. Same philosophy as
the project as a whole (see the [repo root README](../README.md)):
**don't redesign YouTube, change the shell around it.** This is a
normal Android Studio / Gradle project — open it in Android Studio,
or build it from the command line once you have a JDK:

```
./gradlew assembleDebug
```

## What's here

- `app/src/main/java/com/arktube/app/MainActivity.kt` — a `WebView`
  pointed straight at `https://m.youtube.com` over plain HTTPS.
  Unlike a bundled-site shell, there's no `assets/` folder and no
  `WebViewAssetLoader` here — ARKtube's whole point is to wrap the
  *live* site, not ship a copy of it, so this needs the `INTERNET`
  permission (declared in `AndroidManifest.xml`) rather than local
  asset serving. `MainActivity` itself is now a thin Activity-lifecycle
  shell; the actual behavior lives in single-responsibility
  collaborators under `com.arktube.app` (see each package's own class
  docs for the full *why*):
  - `fullscreen/` — everything about fullscreen video:
    `FullscreenVideoController` (facade), `SurfaceViewZOrderNeutralizer`
    (lets the native stretch-to-fill button paint/receive touches above
    Chromium's hardware-composited fullscreen `SurfaceView`),
    `ZoomCropStrategy`/`LetterboxZoomCropStrategy` (crops fullscreen
    video to fill the screen instead of YouTube's letterboxed default,
    by comparing the video's own intrinsic pixel size to its container
    and scaling the native customView with `View.scaleX`/`scaleY` — an
    earlier revision tried this via a CSS transform on the page side
    instead; that never worked on-device, since once YouTube's player
    goes fullscreen WebView promotes the video out of the DOM entirely,
    onto a native View page CSS can no longer reach at all), and
    `StretchToggleButtonFactory` (a manual override button for content
    the automatic crop doesn't catch, e.g. letterbox/pillarbox baked
    into the source video itself rather than added by YouTube's
    player — see `docs/bugs-caught/`).
  - `webview/` — `ArkTubeWebViewFactory` (builds the WebView + its
    settings) and `webview/bridge/` (the `@JavascriptInterface` bridges
    JS uses to report theme, fullscreen video size, and playback state
    back to native code); `ArkTubeScripts` holds every script injected
    into the page.
  - `theme/` — syncs the status/nav bar color to whichever theme
    YouTube itself is rendering (its own light/dark toggle, not the
    phone's system theme).
  - `layout/LayoutReflowHelper` — forces YouTube's off-screen list
    items to re-measure after a rotation.
  - `notifications/` — `NotificationSyncWorker`, a 30-minute WorkManager
    job that polls the signed-in user's own YouTube notification inbox
    (via a short-lived headless second `WebView`, reusing the same
    session cookies rather than a separate OAuth sign-in — see
    `InboxScraper`'s class doc) and mirrors new items as native Android
    notifications.
  - `prefs/` — persisted stretch-to-fill toggle and notification-sync
    "already seen" state.
  - `logging/ArkLogger` — app-wide logger; mirrors warnings/errors to
    an on-device file in addition to Logcat.

  Beyond loading the site, this also:
  - Goes truly edge-to-edge in fullscreen — hides the status bar,
    nav bar (gesture pill or 3-button), *and* draws under the
    notch/camera cutout — rather than just hiding the WebView's own
    chrome and leaving the system bars/cutout inset in place.
  - Keeps the screen from sleeping/locking for as long as fullscreen
    video is on screen (`FLAG_KEEP_SCREEN_ON`), and lets it go back
    to normal once fullscreen ends.
  - Rotates fullscreen video to match the *video's* own orientation
    — landscape upload gets a landscape-locked fullscreen, portrait/
    Shorts gets portrait — the way the YouTube app does, overriding
    the phone's system auto-rotate lock rather than deferring to it.
    Restores whatever orientation you were in before once fullscreen
    ends.
  - Hides YouTube's own "open app" nag button/banner, since this app
    already *is* that experience, just wrapped natively.

  See `docs/bugs-caught/` for the current active-bug list before
  touching any of the above — several of these behaviors have already
  hit (and mostly fixed) the same class of bug: a native write that's
  only safe once per state transition being re-run on every repeated
  WebView JS report.
- `MediaPlaybackService.kt` — a foreground service hosting a real
  `MediaSessionCompat` and `MediaStyle` notification, so play/pause/
  seek/±10s reach the video from *outside* the app entirely: the lock
  screen, the notification shade, a wired headset's inline remote, a
  Bluetooth earbud/car-stereo's AVRCP buttons, a paired watch —
  anything the OS considers a device that can control the active
  media session. `MEDIA_SESSION_JS` (in `MainActivity.kt`) watches the
  page's own `<video>` element and reports its play/pause/seek/title/
  artwork back over a JS bridge, so the session stays truthful even
  when the user hits YouTube's own on-page controls rather than a
  native one. Only promoted into the foreground (which is what posts
  the notification) the first time real playback is reported, not
  eagerly on launch — there's never a "nothing's playing" notification
  sitting in the shade. Handles audio focus (pauses on a call/other
  player taking over) and headphone-unplug, same as any other media
  app.
- `ArkTubeApplication.kt` — enables Material You dynamic color on
  Android 12+; only affects the splash screen and system bars, since
  the WebView content is YouTube's own theming.
- `res/` — a vector-only adaptive icon (dark backdrop, red rounded
  play button) plus raster PNG fallbacks for API 24-25, which predate
  adaptive icons.
- `../.github/workflows/android-build.yml` (at the repo root, not in
  this directory — GitHub Actions only discovers workflow files at a
  repo's top level) — builds a debug APK, smoke-tests it (install +
  launch), and builds a release APK, all on GitHub-hosted runners on
  every push/PR that touches `android-project/`. No JDK, Android SDK,
  or emulator/device needed on your own machine.

The release build is unsigned unless you add `RELEASE_KEYSTORE_BASE64`
(your keystore file, base64-encoded), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, and `RELEASE_KEY_PASSWORD` as secrets in this
repo's Settings → Secrets and variables → Actions — if you do, the
workflow decodes and uses them to sign the APK; if not, the job still
succeeds and gives you an unsigned APK you can sign yourself later.

## Stage 0 scope

Deliberately narrow — the project stays small until the underlying
approach is proven:

- [x] Loads YouTube (mobile web UI, via `m.youtube.com`)
- [x] JS / DOM storage / cookies enabled (so login persists)
- [x] In-app back button walks WebView history before exiting
- [x] Fullscreen video works (`WebChromeClient` custom-view hooks),
      true edge-to-edge (status bar, nav bar, and notch/cutout all
      hidden), cropped to fill rather than letterboxed, oriented to
      match the video's own shape regardless of the rotation lock,
      and keeps the screen awake for as long as it's on screen
- [x] Media-session/notification playback controls (play/pause/seek
      from the lock screen, notification shade, wired headset, or a
      Bluetooth device's own transport buttons)
- [ ] Playback verification on a real device
- [ ] Authentication / session-persistence verification
- [ ] Picture-in-picture
- [ ] Any persistent native chrome (nav shell, sidebar)
- [ ] Download interception, ad-blocking, or other content changes

Everything unchecked is explicitly out of scope for this stage.

## Why `m.youtube.com` and not `youtube.com`

A phone-sized WebView showing the desktop site produces a squeezed,
zoomed-out desktop layout rather than a usable mobile one. Loading
`m.youtube.com` directly gets YouTube's own mobile web UI instead.
