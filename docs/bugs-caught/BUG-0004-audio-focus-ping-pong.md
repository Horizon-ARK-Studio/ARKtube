# BUG-0004: Native AudioFocusRequest fights WebView's own focus request, causing an immediate re-pause on every Play tap

- **Status:** `FIX IMPLEMENTED (diagnostic only), UNVERIFIED ON-DEVICE`
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

**Diagnostic only so far**, per this repo's own rule against patching on a guess:
`audioFocusListener` now logs `onAudioFocusChange focusChange=$focusChange` on every
callback. Correlate against `MediaSessionCoordinator`/`onPlayCommand` log timestamps on
next repro — `LOSS_TRANSIENT` landing within milliseconds of every play tap confirms
this root cause.

**Real fix, not yet implemented:** stop holding a native `AudioFocusRequest` for audio
that Chromium's own WebView media stack already owns and already correctly
pauses/resumes on genuine external interruptions. `ACTION_AUDIO_BECOMING_NOISY`
(already handled separately, unaffected by this bug) covers the headphone-unplug case
independently. `MediaSessionCompat`/the notification only need accurate *state*, which
already arrives for free via the JS bridge's real `play`/`pause`/`ended` events — they
don't require this app to also hold platform audio focus.

## Test

Add the log line (done), reproduce, confirm `LOSS_TRANSIENT` correlates with each
re-pause. Then, once `requestAudioFocus()`/`audioFocusListener` are removed or
reworked: play a video, background/foreground the app, and separately trigger a real
interruption (incoming call) to confirm legitimate focus loss still pauses correctly
without the native request in the loop.

## Notes

Reported and diagnosed in conversation; not yet reproduced against a fresh on-device
capture with the new log line.
