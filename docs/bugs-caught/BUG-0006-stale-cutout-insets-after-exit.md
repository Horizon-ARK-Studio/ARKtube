# BUG-0006: Leftover display-cutout padding strip after exiting fullscreen, only clears on app restart

- **Status:** `FIX IMPLEMENTED, UNVERIFIED ON-DEVICE`
- **Location:**
  `android-project/app/src/main/java/com/arktube/app/fullscreen/FullscreenVideoController.kt`
  — `exitImmersiveFullscreen()`
- **Found:** 2026-09-01
- **Severity:** `Medium`

## Description

After exiting fullscreen video (landscape) back to the normal portrait UI, a blank
strip remains reserved along one screen edge — matching where the display
cutout/notch sits in the *landscape* orientation, not the current portrait one. It
behaves like a ghost: visually present, not interactive, and does not self-correct.
The only thing that clears it is force-closing and reopening the app.

## Expected

Once back in portrait, the layout should reserve space only for the portrait-relevant
system bars/cutout, not leftover landscape-orientation inset geometry.

## Actual

A cutout-shaped padding strip persists in portrait until a full app restart.

## Root cause

`MainActivity.configureWindowForCutout()` sets
`layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` (API 30+) /
`SHORT_EDGES` (API 28-29) once, in `onCreate()`. That's correct and intentional —
it's a one-time, permanent, orientation-independent permission letting the window draw
under the cutout at all; it is not the bug.

The actual gap is one layer up. `FullscreenVideoController.exitImmersiveFullscreen()`
restores `WindowCompat.setDecorFitsSystemWindows(activity.window, true)` — telling
Android to resume auto-padding content around system bars/the cutout — but that call
does not by itself force a *new* `WindowInsets` computation-and-dispatch. The decor
view keeps whatever insets it was last actually handed (computed for the
landscape/immersive window state) until something triggers a fresh pass. A real device
rotation normally gets this for free via Activity recreation; this app deliberately
opts out of recreation via `android:configChanges="orientation|screenSize|
keyboardHidden"` in `AndroidManifest.xml`, which also means it opts out of whatever
insets redispatch would have ridden along with that recreation — same underlying cause
as BUG-0005's reflow gap, one layer further down the platform stack (native window
insets instead of page-side JS layout). Force-restarting the app creates a genuinely
new window/decor view, forcing a fresh insets computation from scratch, which is why
that's the only thing that was clearing it.

## Fix

`exitImmersiveFullscreen()` now calls `ViewCompat.requestApplyInsets(activity.window.
decorView)` immediately after restoring `setDecorFitsSystemWindows(true)`, explicitly
forcing Android to recompute and redispatch current `WindowInsets` down the view tree
instead of leaving the stale landscape-computed value cached.

## Test

Enter fullscreen on a video with the device physically rotated, exit back to portrait,
and confirm no leftover cutout-shaped padding remains along either edge — without
needing to force-close and reopen the app. Repeat across a few consecutive
enter/exit cycles in the same session (not just the first) to rule out the fix only
working on a first-exit edge case.

## Notes

Diagnosed and fixed in conversation; not yet run on-device. If the strip still
appears, the likely next step is checking whether `requestApplyInsets()` here is
firing before the window has actually finished resolving back to portrait (i.e. a
timing race against the asynchronous orientation change triggered by
`activity.requestedOrientation = preFullscreenOrientation`), in which case the call
may need to also (or instead) run from `MainActivity.onConfigurationChanged()`, same
as BUG-0005's reflow call.
