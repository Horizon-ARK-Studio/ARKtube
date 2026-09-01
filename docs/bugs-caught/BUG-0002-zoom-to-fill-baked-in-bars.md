# BUG-0002: Zoom-to-fill doesn't crop letterbox/pillarbox baked into the encoded video itself

- **Status:** `UNFIXED`
- **Found:** 2026-09-01
- **Location:** `android-project/app/src/main/java/com/arktube/app/MainActivity.kt` —
  `applyNativeZoomCrop()`, driven by `VIDEO_SIZE_REPORT_JS` / `OrientationBridge`
- **Severity:** `Medium`

## Description

`applyNativeZoomCrop()` computes its crop scale purely from
`video.videoWidth`/`video.videoHeight` (the encoded frame's own intrinsic pixel size)
versus the fullscreen container's measured size. If a video's letterbox/pillarbox bars
are baked directly into the encoded frame itself — common on older content re-uploaded
into a different aspect ratio — those pixels are indistinguishable from real picture
content at the `videoWidth`/`videoHeight` level, so there is nothing in that comparison
for the crop to detect or remove.

## Expected

"Zoom to fill" removes all visible letterbox/pillarbox bars in fullscreen, matching
what a user would consider a full-bleed edge-to-edge picture.

## Actual

Bars that YouTube's own player adds at render time (the case this feature was built
for) are correctly cropped. Bars that are part of the source video file itself are not,
because the crop math never sees them — it only ever sees the reported intrinsic
dimensions, which already include those bars as "real" pixels.

## Reproduction

1. Enter fullscreen on a video whose source file has hard-coded letterbox/pillarbox
   bars (rather than bars added by YouTube's player for aspect-ratio mismatch).
2. Enable/observe "zoom to fill" — the baked-in bars remain visible.

## Investigated and ruled out

Initially suspected that `VIDEO_SIZE_REPORT_JS`'s size-report dedupe (`lastReportedW`/
`lastReportedH`) doesn't reset between fullscreen sessions, which would silently kill
the crop on a second fullscreen entry for the same video. Checked the actual JS: a
second `fullscreenchange` listener (see `VIDEO_SIZE_REPORT_JS` in `MainActivity.kt`,
around the "A fresh fullscreen session ... should re-report" comment) explicitly resets
both counters and retries at 300ms/1000ms on every fullscreen transition. That path is
correctly guarded — **not** the cause of any "crop stopped working after re-entering
fullscreen" reports, if those occur.

## Likely cause

Structural limitation of driving the crop off `video.videoWidth`/`videoHeight` alone —
that value carries no information about which pixels are "real" picture vs. baked-in
bars.

## Fix

Not attempted. Would need either: (a) accepting this as an out-of-scope limitation and
documenting it for users, or (b) a genuinely different detection method (e.g. sampling
decoded frame edges for near-uniform color, which is a meaningfully bigger feature).

## Test

TBD if pursued. Would need a supplied test video with known baked-in bars.

## Notes

Also flagged in passing: `android-project/README.md`'s Stage-0 section still describes
the *earlier, abandoned* CSS-transform approach to this feature ("cropping ... by
comparing the video's own intrinsic pixel size to its container and scaling with a CSS
transform") as if it's the shipped implementation. `MainActivity.kt`'s own comments say
that approach never worked on a real device and was fully replaced by the current
native `View.scaleX`/`scaleY` approach. Not a functional bug, but worth fixing so
nobody re-debugs the wrong mechanism later.
