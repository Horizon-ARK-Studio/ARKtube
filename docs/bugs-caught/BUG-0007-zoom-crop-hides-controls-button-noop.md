# BUG-0007: Zoom-to-fill scales YouTube's control chrome off-screen; the stretch-toggle button couldn't undo it

- **Status:** `PARTIAL FIX IMPLEMENTED, UNVERIFIED ON-DEVICE`
- **Found:** 2026-09-01
- **Location:**
  `android-project/app/src/main/java/com/arktube/app/fullscreen/FullscreenVideoController.kt`
  (`applyZoomCrop()`) and
  `android-project/app/src/main/java/com/arktube/app/fullscreen/StretchToggleButtonFactory.kt`
- **Severity:** `Medium`

Distinct from BUG-0002 (baked-in encoded bars the crop can't detect at all). This bug
is about the crop successfully removing letterbox bars, at the cost of also hiding
reachable UI.

## Description

Two related but separate defects, found together:

**(a) Zoom-to-fill makes YouTube's own player controls (settings gear, captions
toggle, etc.) unreachable.** `applyZoomCrop()` applies `view.scaleX`/`scaleY` to
`customView` — the single, opaque, hardware-composited surface Chromium hands over in
`onShowCustomView()`, which contains both the video content and Chromium's own native
control chrome baked into the same surface. A uniform `View` transform scales
everything that surface renders together; there is no way from outside to scale only
the video while leaving controls pinned at their original position. Past a real crop
factor, controls anchored near the original edges get pushed outside the container's
clip bounds entirely.

**(b) The stretch-toggle button, added as a workaround, had no actual effect** for the
exact case it was meant to help with. `LetterboxZoomCropStrategy.compute()`'s
`forceFillEnabled` parameter only ever moved the apply-threshold (1.01 vs. 1.001); it
never produced a path back to `scale = 1f` once real, visible letterboxing was already
present. For any video with genuine letterbox bars — precisely the case where controls
go off-screen — the crop was already being auto-applied before the button was ever
touched, so toggling `forceFillEnabled` changed nothing: same computed `scale`, same
`shouldApply` result, either side of the threshold. The button's label did visibly
toggle (confirming touch dispatch, z-order, and the click listener were never the
problem), which is what made this look like "does nothing" rather than an obvious
crash or missing wiring.

## Expected

(a) is a hard architectural limit of a whole-surface-transform crop and isn't fully
fixable without replacing Chromium's native control chrome with fully custom overlay
controls positioned outside the transform. (b) is a real, fixable bug: an
already-added "get me back to the controls" button should actually do that.

## Actual

(a) unchanged/still a real limitation — documented here rather than fixed. (b) fixed:
the button now genuinely toggles between the automatic crop and a hard
`scale = 1f` override.

## Root cause

(a): see Description — no selective-transform capability exists at the `View.scaleX/
scaleY` level for a single opaque composited surface.

(b): `ZoomCropStrategy`'s boolean parameter was designed for a different purpose
("make automatic cropping slightly more aggressive on a near-exact aspect match")
than what it was actually being used for by the time the button existed ("let me
manually undo the crop to reach hidden controls"). Those are different operations;
the threshold-nudge one can never produce the manual-override one's result.

## Fix

`FullscreenVideoController.applyZoomCrop()`: when `forceFillPreference.isEnabled` is
true, now bypasses `ZoomCropStrategy` entirely and forces `scale = 1f` directly,
instead of passing the flag through to `compute()`'s threshold. `compute()` is now
always called with `forceFillEnabled = false`, since the manual-override path is
handled above it and the threshold-nudge behavior is no longer reachable through this
preference.

`StretchToggleButtonFactory.applyAppearance()`: label updated from `FILL✓`/`FILL` to
`FIT✓`/`FILL` to describe what the checked state now actually does (escape back to
unscaled, not force more crop).

**Known naming smell, deliberately not fixed here:** `ForceFillPreference` / the
`force_fill_enabled` `SharedPreferences` key now mean the *opposite* of what their
names say — checked means "un-force the fill, go back to unscaled." Not renamed in
this pass because the class also owns the persisted preference key; renaming it would
require an explicit read-old/write-new/drop-old migration to avoid silently resetting
every existing user's saved preference, which is a deliberate follow-up, not a
same-diff rename.

(a) is not fixed and isn't expected to be by a code change of this shape. Left as an
open, documented architectural limitation.

## Test

Enter fullscreen on a video with real, visible letterbox bars. Confirm the automatic
crop still applies as before. Tap the stretch-toggle button and confirm the video
visibly returns to unscaled/letterboxed (bars back, previously-offscreen controls
reachable again). Tap again and confirm the automatic crop reapplies.

## Notes

(a) is worth a decision, not just a fix: either accept it as a documented limitation
of the current native-control-chrome approach, or scope replacing Chromium's fullscreen
controls with fully custom overlay controls (bigger feature, but the only real fix for
controls-go-off-screen rather than a workaround for it).
