# BUG-0005: YouTube's page-side player-layout JS stays in landscape/fullscreen mode after exiting native fullscreen

- **Status:** `FIX IMPLEMENTED, UNVERIFIED ON-DEVICE`
- **Found:** 2026-09-01
- **Location:**
  `android-project/app/src/main/java/com/arktube/app/fullscreen/FullscreenVideoController.kt`
  (`onExitFullscreen` callback, `hideCustomView()`) and
  `android-project/app/src/main/java/com/arktube/app/MainActivity.kt`
  (`fullscreenController` construction)
- **Severity:** `Medium`

## Description

Not an orientation-lock bug (the native `requestedOrientation`/window/Activity layer
returns to portrait correctly — see BUG-0003, a separate, already-addressed issue).
After exiting fullscreen video, the page itself (YouTube's own JavaScript, not this
app's Kotlin) continues behaving as though it's still in its landscape/fullscreen
player-layout mode.

## Expected

Once native fullscreen exits and the WebView returns to portrait, YouTube's own page
JS should re-measure and render its normal embedded-player layout.

## Actual

The page-side player layout stays stuck as if still fullscreen/landscape until the app
is force-closed and reopened (i.e. an actual new WebView/window, not just a rotation).

## Root cause

Same mechanism already identified for `LayoutReflowHelper`/`FORCE_REFLOW_JS` (the
"Up next" related-videos row not re-measuring after rotation): Chromium's own internal
compositor correctly reflows the WebView's pixels, but content JS listening for the
`resize`/`orientationchange` **DOM events** specifically doesn't reliably get told just
because the underlying Android View was resized by the framework rather than a real
browser-window resize. YouTube's internal player-layout-mode logic is a second,
independent consumer of that same signal, separate from the "Up next" row.

The gap: `LayoutReflowHelper.reflow()` was only ever invoked from
`MainActivity.onConfigurationChanged()` — a platform callback that only fires if
Android's resolved `Configuration` actually differs afterward, and is not guaranteed to
land after (or ever, for) a fullscreen-exit transition specifically.
`FullscreenVideoController.hideCustomView()` — the one call site that knows with
certainty a fullscreen exit just happened — never triggered a reflow at all.

## Fix

`FullscreenVideoController` now takes an `onExitFullscreen: () -> Unit` callback,
invoked at the end of `hideCustomView()`. `MainActivity` wires this to
`layoutReflowHelper.reflow { fullscreenController.isShowing }` — the same call
`onConfigurationChanged()` already makes — so the reflow now fires unconditionally on
exit, independent of whether a `Configuration` callback also happens to land
afterward.

## Test

Enter fullscreen on a video, exit, and confirm (a) YouTube's own player renders its
normal embedded layout immediately, not just after a subsequent rotation or app
restart, and (b) the "Up next" row (BUG-0001/pre-existing behavior) still re-measures
correctly, i.e. this didn't regress the original reflow fix's target.

## Notes

Diagnosed and fixed in conversation; not yet run on-device. If the reflow still lands
too early relative to the WebView's own actual resize completing, the next step is
correlating `LayoutReflowHelper` log timestamps against the point the page visually
updates, and potentially also firing from `onConfigurationChanged()` in addition to
(not instead of) `hideCustomView()`.
