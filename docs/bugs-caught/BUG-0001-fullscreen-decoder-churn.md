# BUG-0001: Video decoder repeatedly releases/recreates during normal playback ("pauses instantly, again and again")

- **Status:** `UNFIXED`
- **Found:** 2026-09-01
- **Location:** Not yet isolated to a specific file/line. Reproduced on-device; root cause is
  still open between `android-project/app/src/main/java/com/arktube/app/MainActivity.kt`,
  `MediaPlaybackService.kt`, and (candidate, unconfirmed) YouTube's own player inside the WebView.
- **Severity:** `Critical`

## Description

During normal (non-fullscreen, no device rotation) playback of a video loaded via the
in-app WebView, the AV1 hardware decoder (`c2.qti.av1.decoder`) and its `AudioTrack`
are repeatedly stopped and released, then recreated from scratch, on a roughly
2-second cadence. Each cycle is audible/visible as the video pausing and picking back
up. The device is stationary and in-page (no fullscreen, no rotation) for the whole
capture window.

## Expected

Once a video starts playing, the same decoder/`AudioTrack` instance should keep
running uninterrupted for the duration of playback (aside from legitimate
user-initiated seeks/pauses or real network stalls).

## Actual

Reproducible, tight stop -> release -> recreate -> start loop. Two consecutive full
cycles captured back-to-back:

```
13:30:52.065  AudioTrack stop   (prior state: STATE_ACTIVE)
13:30:52.075  cr_MediaCodecBridge: Releasing: c2.qti.av1.decoder
13:30:52.655  AudioTrack start  (prior state: STATE_STOPPED)
13:30:52.670  cr_MediaCodecBridge: create MediaCodec video decoder ... c2.qti.av1.decoder

13:30:54.202  AudioTrack stop   (prior state: STATE_ACTIVE)   <- 2.137s after previous stop
13:30:54.212  cr_MediaCodecBridge: Releasing: c2.qti.av1.decoder
13:30:54.636  AudioTrack start  (prior state: STATE_STOPPED)
13:30:54.634  cr_MediaCodecBridge: create MediaCodec video decoder ... c2.qti.av1.decoder
```

Each recreated decoder configures to `video/av01, 640x360` (360p) and immediately logs
`Failed to query component interface for required system resources: 6` before it starts
successfully.

This loop was **not** occurring on a clock forever — later in the same session (after the
app was backgrounded ~5 minutes and brought back / the user rotated the device a few
times) the cadence changed and the tight 2s loop was not observed again in the same form,
so the trigger condition is not fully understood yet.

## Reproduction

1. Launch ARKtube, open a video from the home feed (non-fullscreen, in-page player).
2. Let it play, phone stationary, no interaction.
3. Watch logcat for `cr_MediaCodecBridge: Releasing` / `create MediaCodec video decoder`
   pairs recurring every ~2s, each preceded by an `AudioTrack stop`.

## Investigated and ruled out

Two previously-fixed bugs in this codebase produce an outwardly identical symptom
("fullscreen video pausing/resuming in a loop"), and were initially suspected as the
cause here. Both were re-verified against the current `Android` branch HEAD
(`git log --all` shows a single linear history for the affected files, nothing after
either fix reverts or weakens it) and both are ruled out for *this* reproduction:

- **SurfaceView z-order thrash** (fixed in `a44d394`, guarded by
  `surfaceViewZOrderNeutralized` in `MainActivity.kt`): this only fires off a fullscreen
  layout pass (rotation, inset changes). This capture has zero fullscreen/rotation
  events (`DeviceStatusMonitor: rotation changed` never appears) and the loop still
  reproduces, so this is not the trigger here.
- **Repeated `AUDIOFOCUS_GAIN` request** (fixed in `970513d`, guarded by `wasPlaying` in
  `MediaPlaybackService.updatePlaybackState()`): this has a single call site
  (`MainActivity.MediaPlaybackBridge.onPlaybackState`) with the edge-guard intact, and
  the observed ~2.1s period doesn't line up with the ~1s JS `timeupdate` throttle the
  way a broken guard would produce. Not ruled out with certainty, but doesn't fit.

## Leading (unconfirmed) hypothesis

The same logcat is full of YouTube's own ad-analytics beacons
(`www.youtube.com/pagead/interaction/...`) failing with:

> Access to XMLHttpRequest ... has been blocked by CORS policy: The value of the
> 'Access-Control-Allow-Origin' header ... must not be the wildcard '\*' when the
> request's credentials mode is 'include'.

repeated for essentially every beacon the page tries to send, alongside the player
picking a low resolution (`640x360`) stream. Both are consistent with YouTube's player
treating this WebView as a degraded/less-trusted client and reacting defensively
(frequent ABR/decoder reconfiguration) — which would make this a consequence of how
`m.youtube.com`'s player behaves inside a bare `WebView` rather than a bug in ARKtube's
own Kotlin code. **Not confirmed.** Per discussion, the low resolution itself is being
treated as a symptom, not the root cause — the decoder churn is the actual bug being
tracked here regardless of what quality it's churning at.

## Fix

Not yet identified — diagnosis is still open. Do not guess-and-patch native code again
without first isolating native-code-cause vs. page-side-cause (see Notes).

**Update 2026-09-01, later same day:** A concrete, code-level root-cause candidate was
found by re-reading every script in `ArkTubeScripts.kt` against the pattern this
codebase already uses (and had already fixed twice) for repeated-report functions: an
operation that's only safe once per state transition must not be re-run on every
repeated report (see `MediaPlaybackService.updatePlaybackState()`'s own doc comment for
the `wasPlaying` guard fixed in `970513d`, and the `surfaceViewZOrderNeutralized` guard
fixed in `a44d394`). `VIDEO_SIZE_REPORT_JS`'s `reportSize()` and `MEDIA_SESSION_JS`'s
`reportInfo()` both have that guard (`if (x === lastReportedX) return`).
`THEME_SYNC_JS`'s `report()` did **not** — it unconditionally called
`window.ArkTubeTheme.onThemeChanged(dark, bg)` every time it ran, with zero check
against what was last reported.

`report()` is wired to two triggers: a `MutationObserver` on `<html>`/`<body>`
attributes (which YouTube's SPA mutates constantly during normal playback -- scroll
lock, player-active state, ad states, etc.) and, as a backstop, `setInterval(report,
2000)` — **a literal 2000ms period**, which lines up almost exactly with this bug's
captured ~2.1s stop/release cadence. Every one of those calls reaches
`MainActivity`'s `themeListener` → `StatusBarThemeApplier.apply()`, which itself also
had no dedupe and unconditionally rewrote `window.statusBarColor`,
`window.navigationBarColor`, and both `WindowInsetsControllerCompat` appearance flags
on every call — real window/inset writes, not no-ops, even when the color was
identical to the previous call.

**Fix implemented (unverified on-device):**
- `THEME_SYNC_JS`'s `report()` (`webview/ArkTubeScripts.kt`) now tracks
  `lastReportedDark`/`lastReportedBg` and returns early when neither has changed,
  matching `reportSize()`/`reportInfo()`'s existing shape.
- `StatusBarThemeApplier.apply()` (`theme/StatusBarThemeApplier.kt`) also now tracks
  `lastAppliedColor`/`lastAppliedIsDark` and no-ops on a redundant call, as
  defense-in-depth independent of the JS-side fix.

This directly explains the *cadence* (2000ms interval, matching the ~2.1s captured
gap) and identifies a genuine, previously-unguarded repeated native-window-write path
triggered from WebView JS on every theme-sync tick — which fits "the playback pause is
caused by something interacting with the WebView incorrectly" exactly. It does **not**
yet explain, with hard evidence, *why* repeatedly rewriting `statusBarColor`/inset
appearance flags would specifically cause the AV1 decoder to release/recreate rather
than just being wasted work — that mechanism (window/inset churn forcing a
WebView surface reconfiguration) is plausible given Android's edge-to-edge/inset
system but not yet confirmed against a captured trace. Per this bug's own prior notes
and BUG-0003's notes, this should not be treated as fixed until logcat confirms the
decoder-churn cadence actually stops after this change.

## Test

TBD once root cause is confirmed. At minimum: play a video for 60s+ stationary,
in-page, and confirm zero unexpected `MediaCodec`/`AudioTrack` release-recreate cycles
in logcat.

**Added by the 2026-09-01 update above:** with the fix in place, also confirm via
logcat that `ArkLogger`'s `StatusBarThemeApplier.apply` / `themeListener` track logs
now fire only on genuine theme changes (once per page load, plus real light/dark
toggles) rather than on a ~2s cadence. If the decoder-churn loop stops in the same
capture where those logs also stop firing on a timer, that's strong confirming
evidence for this root cause; if the churn continues even with theme-sync calls now
suppressed, this fix should be considered ruled out (not just insufficient) and the
next candidate (page-side/YouTube-player ABR behavior, per the CORS/low-resolution
hypothesis below) should be investigated instead.

## Notes

Proposed next diagnostic step (not yet performed): temporarily disable
`MEDIA_SESSION_JS` injection and skip binding `MediaPlaybackService` entirely, then
reproduce. If the ~2s stutter still occurs with zero native playback-control code in
the loop, the cause is on the page/YouTube-player side, not in this app's Kotlin. If it
stops, the bridge/native code is implicated after all and needs its own trace.
