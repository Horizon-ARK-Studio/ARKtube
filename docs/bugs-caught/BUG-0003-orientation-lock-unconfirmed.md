# BUG-0003: Fullscreen orientation lock possibly self-reasserting (unconfirmed)

- **Status:** `UNFIXED`
- **Found:** 2026-09-01
- **Location:** `android-project/app/src/main/java/com/arktube/app/MainActivity.kt` —
  `applyFullscreenOrientation()`, called from `OrientationBridge.onFullscreenVideoSize()`
- **Severity:** `Low` (evidence is circumstantial; not yet independently reproduced)

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

`applyFullscreenOrientation()`/`preFullscreenOrientation` entry/exit bookkeeping
(snapshot on entry via `onShowCustomView`, restore on exit via `onHideCustomView`) reads
as internally consistent — no obvious double-snapshot or unrestored-state bug in the
code itself. The open question is specifically whether re-writing
`android:screenOrientation`/`requestedOrientation` to an *already-current* value can
itself cause a transient rotation blip on this device/OEM build (vivo), which would be
a platform quirk this app would need to work around (e.g. by skipping the write when
the target orientation hasn't actually changed) rather than a logic error in the
lock/restore bookkeeping.

## Fix

Not attempted — pending confirmation. If confirmed, the likely fix is to no-op
`applyFullscreenOrientation()` when the computed target orientation already matches the
current `requestedOrientation`, so the three near-simultaneous calls from the JS retry
schedule can't each trigger a fresh write.

## Test

TBD once reproduced with an added log line around every `requestedOrientation` write.

## Notes

Add a log line at every `requestedOrientation` assignment in `applyFullscreenOrientation()`
before touching any behavior here — this is exactly the kind of "looks correct, isn't
confirmed" lead that shouldn't be patched on a guess (see BUG-0001's notes on the same
principle).
