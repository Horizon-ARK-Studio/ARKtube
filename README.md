# ARKtube — Android

## YouTube, as an Android app.

A lightweight Android client that wraps YouTube's own mobile web UI
(`m.youtube.com`) in a native shell.

It looks like YouTube.

It behaves like an app.

No redesign.
No bundled copy of the site.
Just YouTube, with the native chrome a browser tab doesn't give you.

---

## The idea

YouTube's mobile website is already a great product.

It just isn't an app — no real fullscreen, no OS-level media
controls, no native splash/status-bar integration, and the browser's
own UI is always one swipe away.

This project keeps the familiar YouTube experience while wrapping it
in exactly enough native shell to fix that:

```text
YouTube (m.youtube.com)
        +
  a WebView, native fullscreen,
  and a real media session
        =
YouTube, installed.
```

---

## What this is

**A native Android shell around YouTube's own mobile site** — a
`WebView` pointed at `https://m.youtube.com`, not a bundled or
scraped copy of it. See [`android-project/README.md`](android-project/README.md)
for the full implementation breakdown; in short, this stage adds:

* Fullscreen video cropped to fill the screen instead of YouTube's
  own letterboxed default
* True edge-to-edge fullscreen — status bar, nav bar, and notch/
  cutout all hidden, not just the WebView's own chrome
* Fullscreen orientation locked to the *video's* own shape (landscape
  upload → landscape, Shorts/portrait → portrait), overriding the
  phone's rotation lock the way the official YouTube app does
* Screen stays awake for as long as fullscreen video is on screen
* Status/nav bar color synced to whichever theme YouTube itself is
  rendering
* OS-level media session integration — play/pause/seek reach the
  video from the lock screen, the notification shade, a wired
  headset, or a Bluetooth device's own transport buttons, not just
  from inside the app

## What this is not

This isn't:

* a YouTube redesign
* a Piped/NewPipe client
* an ad-blocker or download tool
* a bundled/offline copy of the site

There are already enough of those. This project is interested in
something narrower:

> **What if the YouTube mobile site behaved like the app it already
> looks like?**

---

## Status

🚧 **Stage 0 — experimental**

The current milestone is proving the WebView-shell approach holds up:
fullscreen, orientation, theming, and OS media controls all work
correctly on a real device, on top of the ordinary YouTube experience
(playback, search, authentication, navigation) working exactly as it
does in a normal browser tab. See
[`android-project/README.md`](android-project/README.md#stage-0-scope)
for the current checklist.

---

## Development

This is a normal Android Studio / Gradle project, in
[`android-project/`](android-project/).

```bash
cd android-project
./gradlew assembleDebug
```

Or open `android-project/` directly in Android Studio. See
[`android-project/README.md`](android-project/README.md) for what's
in there, the release-signing setup, and the CI workflow
(`.github/workflows/android-build.yml`) that builds and smoke-tests
every push.

Requires a JDK; no Android SDK/emulator install is required just to
build a debug APK (Gradle fetches what it needs), though you'll want
one to actually run/debug the app.

---

## Philosophy

Keep the UI.

Keep the player.

Keep the experience.

Change the shell.

The project should add as little as possible. If YouTube's own
mobile site already solves a problem, let it solve it. If the app
needs something the site doesn't provide — real fullscreen, native
media controls — add the smallest native layer necessary.

---

## License

This project contains original code written for the Android shell
and application layer.

YouTube is a trademark of Google LLC.

This project is independent and is not affiliated with or endorsed by
Google or YouTube.

See the repository license for the licensing terms of this project.
