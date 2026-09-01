# BUG-0004: Native AudioFocusRequest fights WebView's own focus request, causing an immediate re-pause on every Play tap

- **Status:** `FIX IMPLEMENTED, UNVERIFIED ON-DEVICE`
- **Found:** 2026-09-01
- **Location:** `android-project/app/src/main/java/com/arktube/app/MediaPlaybackService.kt`
  — `audioFocusListener` (~line 108) and `requestAudioFocus()` (~line 305)
- **Severity:** `Critical`

## Description

Distinct from BUG-0001 (an *autonomous* ~2.1s decoder-churn loop while stationary,
already attributed to unguarded theme-sync JS). This bug is *interaction-driven*: video
plays once, pauses on its own shortly after, and every subsequent tap of Play (from the
notification, lock screen, or the on-page control) results in the video pausing again
almost immediately.

## Expected

Once playback starts, it continues until the user, a real external interruption (phone
call, another app taking audio focus), or a genuine headset/Bluetooth disconnect pauses
it.

## Actual

Play → plays briefly → pauses on its own → user taps Play → pauses again immediately →
repeat, indefinitely.

## Reproduction

1. Launch ARKtube, open any video.
2. Observe it pause shortly after starting, with no user interaction.
3. Tap Play (notification, lock screen, or in-page). Observe it pause again within
   milliseconds.
4. Repeat step 3 — same result every time.

## Root cause (traced by code reading, not yet confirmed on-device)

`MediaPlaybackService.updatePlaybackState()` correctly guards against *redundant*
`requestAudioFocus()` calls on repeated "still playing" reports via the `wasPlaying`
edge-check (fixed in `970513d`) — that fix is real and correct for what it targets.

What it doesn't address: this app is not the only audio-focus requester for the video's
audio stream. WebView/Chromium's own internal media stack independently requests
`AudioManager` focus for the same `<video>` element, in the same process. So:

1. Video starts → Chromium requests focus for its own playback → gets it.
2. `MediaPlaybackService.updatePlaybackState(playing=true)` fires on the edge → calls
   `requestAudioFocus()` (a *legitimate*, once-per-play-start call, not a bug on its
   own) → this app's request wins → Chromium's request is evicted → Chromium, honoring
   its own focus-loss handling, pauses the real `<video>` element.
3. User taps Play → `video.play()` → Chromium needs the speaker back, re-requests focus
   → evicts *this app's* native request → `audioFocusListener` receives
   `AUDIOFOCUS_LOSS_TRANSIENT` → unconditionally calls `commandListener?.onPauseCommand()`
   → `video.pause()` fires immediately.
4. Notification flips to Paused, user taps Play, go to step 3.

Two independent audio-focus requesters, same PID, same physical stream, both correctly
honoring focus-loss per platform etiquette — which is exactly what makes them fight.

## Fix

Implemented: `MediaPlaybackService` no longer holds a native `AudioFocusRequest` at
all. Removed `requestAudioFocus()`, the `audioFocusListener`
(`OnAudioFocusChangeListener`), the `audioFocusRequest` field, the now-unused
`audioManager` field, and the abandon calls in `onDestroy()`. This app was never the
only legitimate owner of focus for the `<video>` element's audio stream — Chromium's
own WebView media stack already requests it and already pauses/resumes correctly on a
genuine external interruption (a call starting, another app's playback), per the
platform's own etiquette. Holding a second, competing `AudioFocusRequest` for the same
physical stream bought this app nothing and caused the ping-pong. See
`docs/Foundational/SYSTEM-DESIGN-AGREEMENTS.md` — this is that document's namesake
failure shape: two well-behaved systems both claiming the same platform resource.

`ACTION_AUDIO_BECOMING_NOISY` (headphone unplug / Bluetooth disconnect) is unrelated to
audio focus and is untouched — still registered/handled directly in
`becomingNoisyReceiver`, same as before. `MediaSessionCompat`/the notification still
reflect accurate play/pause *state*, which arrives for free via the JS bridge's real
`play`/`pause`/`ended` events through `updatePlaybackState()` — that path never needed
platform audio focus to stay truthful, only the erroneous *request* did.

## Test

Not yet run on-device. To confirm:

1. Play a video. Confirm it does **not** self-pause shortly after starting (the
   original symptom).
2. Tap Play from the notification, lock screen, and in-page repeatedly. Confirm it
   stays playing instead of re-pausing within milliseconds of each tap.
3. Background/foreground the app during playback — confirm playback and transport
   controls remain correct.
4. Trigger a genuine external interruption (incoming call, another media app starting
   playback) — confirm Chromium still pauses the video correctly (via its own focus
   handling, no longer this app's), and that resuming afterward still works from both
   the page and the native transport controls.
5. Unplug headphones / disconnect Bluetooth mid-playback — confirm
   `ACTION_AUDIO_BECOMING_NOISY` still pauses correctly (unrelated code path,
   regression check only).

Per this repo's own bug-tracker rules
(`docs/bugs-caught/README.md` → Verification Standard), this entry stays listed as
**unverified** — not removed — until the above steps pass on a real device.

## Notes

Root-cause diagnosis (conversation) traced this to a two-owner conflict over one
`AudioManager` focus slot: Chromium's own WebView media stack vs. this app's native
`AudioFocusRequest`. The prior diagnostic-only revision added a log line to confirm
`LOSS_TRANSIENT` timing before committing to a fix; that diagnostic is now moot since
the request-holding code it was instrumenting has been removed outright rather than
reworked. Generalized as a named class of bug (not just this one instance) in the new
`docs/Foundational/SYSTEM-DESIGN-AGREEMENTS.md`, alongside BUG-0001's SurfaceView/
decoder churn, which is the same failure shape over a different platform resource.
