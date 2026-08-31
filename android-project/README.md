# ARKtube — Android edition (Stage 0)

Stage 0 scaffold for an Android build of ARKtube. Same philosophy as
the desktop app (see `../docs/PROBLEM-STATEMENT.md`): **don't redesign
YouTube, change the shell around it.** This is a normal Android
Studio / Gradle project — open it in Android Studio, or build it from
the command line once you have a JDK:

```
./gradlew assembleDebug
```

## What's here

- `app/src/main/java/com/arktube/app/MainActivity.kt` — the whole
  app: a `WebView` pointed straight at `https://m.youtube.com` over
  plain HTTPS. Unlike a bundled-site shell, there's no `assets/`
  folder and no `WebViewAssetLoader` here — ARKtube's whole point is
  to wrap the *live* site, not ship a copy of it, so this needs the
  `INTERNET` permission (declared in `AndroidManifest.xml`) rather
  than local asset serving.
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

Deliberately narrow, matching the desktop README's "the project will
stay deliberately small until the underlying approach is proven":

- [x] Loads YouTube (mobile web UI, via `m.youtube.com`)
- [x] JS / DOM storage / cookies enabled (so login persists)
- [x] In-app back button walks WebView history before exiting
- [x] Fullscreen video works (`WebChromeClient` custom-view hooks)
- [ ] Playback verification on a real device
- [ ] Authentication / session-persistence verification
- [ ] Picture-in-picture
- [ ] Media-session / notification playback controls
- [ ] Any persistent native chrome (nav shell, sidebar)
- [ ] Download interception, ad-blocking, or other content changes

Everything unchecked is explicitly out of scope for this stage — see
the desktop app's roadmap (`../README.md`) for how later stages are
expected to build on this.

## Why `m.youtube.com` and not `youtube.com`

A phone-sized WebView showing the desktop site produces a squeezed,
zoomed-out desktop layout rather than a usable mobile one. Loading
`m.youtube.com` directly gets YouTube's own mobile web UI instead —
same approach the desktop app takes with `youtube.com` on desktop
(see `../docs/PROBLEM-STATEMENT.md`).
