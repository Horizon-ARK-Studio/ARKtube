# BUG-0003: Fullscreen orientation lock possibly self-reasserting

- **Status:** `FIX IMPLEMENTED, UNVERIFIED ON-DEVICE`
- **Found:** 2026-09-01
- **Location:** `android-project/app/src/main/java/com/arktube/app/fullscreen/FullscreenVideoController.kt`
  — `applyOrientationLock()`, called from `onFullscreenVideoSize()`
  (post-refactor path; originally `MainActivity.applyFullscreenOrientation()`)
- **Severity:** `Low` (visible as a brief rotation blip, not a hard failure)

## Description

An earlier capture (before this file was added) showed the display rotating and
un-rotating on its own — `DeviceStatusMonitor: Display 0 rotation changed: 0 -> 1` then
`1 -> 0` about 3.5s apart — shortly after entering fullscreen, with no corresponding
user action visible in the log. This coincided with `VIDEO_SIZE_REPORT_JS`'s
size-report retry schedule (immediate `requestAnimationFrame` + `setTimeout(300)` +
`setTimeout(1000)`, see the `fullscreenchange` handling in `VIDEO_SIZE_REPORT_JS`),
which calls `applyFullscreenOrientation()` up to three times in quick succession right
after entering fullscreen — each time reasserting the *same* `requestedOrientation`
value.

## Expected

Fullscreen locks to the video's own orientation once, cleanly, without any visible
rotation bounce.

## Actual (circumstantial, single capture)

A display rotation event fired and reversed within ~3.5s of entering fullscreen, on a
vivo device, with no user interaction logged in between.

## Reproduction

Not yet reliably reproduced in isolation — this is a read of one earlier logcat
capture, correlated by timing rather than confirmed by an added log statement. Needs a
dedicated repro: enter fullscreen on a landscape video while holding the phone in
portrait, and watch `DeviceStatusMonitor`/`requestedOrientation` writes specifically.

## Investigated

`preFullscreenOrientation` entry/exit bookkeeping (snapshot on entry via
`showCustomView()`, restore on exit via `hideCustomView()`) reads as internally
consistent — no double-snapshot or unrestored-state bug there. The open question was
whether re-writing `requestedOrientation` to an *already-current* value can itself cause
a transient rotation blip.

**Traced end to end (2026-09-01, later update):** confirmed via code reading, not yet
via device repro. `VIDEO_SIZE_REPORT_JS`'s `reportSize()` dedupes *before* calling the
bridge, but only against the exact previous pixel size (`lastReportedW`/`H`) — and its
`fullscreenchange` handler resets both to `0` on every fullscreen entry, then retries at
+300ms and +1000ms. A byte-identical size across those retries is correctly suppressed —
but a legitimate mid-ramp-up ABR resolution change (e.g. an initial low-buffer 640x360
stepping up to 1920x1080 shortly after entering fullscreen, both landscape) is a real
size change, so JS correctly re-reports it. `applyOrientationLock()` had no concept of
"same category, skip the write" — it recomputed `SENSOR_LANDSCAPE`/`SENSOR_PORTRAIT` from
raw width/height and wrote `requestedOrientation` unconditionally every time, so two
JS-reported sizes in the same orientation category within ~300-1000ms of each other
produced two back-to-back writes of the *same* enum value. Same "operation only safe
once per state transition, re-run on every repeated report" shape as
`SurfaceViewZOrderNeutralizer`, `MediaPlaybackService.requestAudioFocus()`, and
`StatusBarThemeApplier.apply()` — this was the one place in the app that pattern hadn't
been fixed yet.

## Fix

**Implemented:** `applyOrientationLock()` now compares the computed target orientation
against `activity.requestedOrientation` and no-ops (with a debug log) when they already
match, only writing on an actual category change — same shape as the other three
already-fixed guards.

## Test

Play a landscape video that ramps up in resolution shortly after entering fullscreen
(or force a quality switch manually), phone stationary, and confirm in logcat that
`FullscreenVideoController` logs `applyOrientationLock: no-op, orientation category
unchanged` for the repeat report(s) instead of a second `requestedOrientation` write —
and that `DeviceStatusMonitor: Display 0 rotation changed` does not fire a second time
in the same fullscreen entry with no user interaction.

## Notes

Root-caused by static trace rather than an added log line + device repro, per this
bug's own prior caution against patching on a guess — the mechanism above is a direct
reading of `VIDEO_SIZE_REPORT_JS`'s dedupe logic against `applyOrientationLock()`'s
lack of one, not circumstantial timing correlation. Still worth confirming on-device
before considering this fully closed, same as BUG-0001's fix.
