# ARKtube -- System Design Agreements

**Status:** Living document
**Scope:** whole project (the principle is architectural, not file-specific)

`CODE-STYLE.md` explains how a piece of code should be shaped once
you know what it owns. This document is one level up: **who is
allowed to own what**, between this app's native layer and the
WebView/Chromium runtime it's built on top of.

This isn't a style preference. It's the recurring root cause behind
this project's worst bugs.

this is from Android branch. but the points still hold for desktop shell as well.

---

## The agreement

> **WebView/Chromium is not a passive rendering surface this app
> controls. It is an independent runtime with its own opinions about
> media, layout, and focus. Every native subsystem in this app must
> either (a) defer entirely to what Chromium already owns, or (b)
> own something Chromium provably does not touch -- never (c) hold a
> second, competing claim over the same platform resource.**

Two well-behaved systems that both think they're in charge of the
same resource don't average out to correct behavior. Each one reacts
to the *other's* actions as if they were external reality, and the
result is a fight neither side can win -- which looks, from the
outside, like the app randomly sabotaging itself.

## Why this is a *system design* class of bug, not "just bugs"

Stage 1's two worst bugs so far are the same failure shape wearing
different clothes:

| | Resource both sides think they own | What "winning" looks like from the loser's side |
|---|---|---|
| **BUG-0001** | The video's `MediaCodec` decoder / `SurfaceView` z-order during theme-sync | Chromium sees its `SurfaceView` knocked around, tears down and recreates the decoder -- playback stutters/pauses roughly every ~2.1s, indefinitely |
| **BUG-0004** | `AudioManager` focus for the `<video>` element's one physical audio stream | Chromium sees `AUDIOFOCUS_LOSS_TRANSIENT` on its own request, honors it like any well-behaved player, pauses the real `<video>` |

Neither bug involved broken logic in isolation. `requestAudioFocus()`
was implemented correctly, exactly once per play-start edge, exactly
per the platform's own audio-focus documentation. The unguarded
theme-sync JS in BUG-0001 correctly kept the page's theme in sync.
**Both were locally correct and globally wrong**, because both
assumed they were the only actor touching a resource Chromium was
already actively managing. That's not a coding mistake a linter or a
unit test catches -- it's a missing answer to "who owns this?" at
design time, and it will keep recurring at the file/PR level until
it's answered once, here, and applied everywhere this app talks to
the runtime underneath it.

This matters most for whoever is moving fast and trusting the
platform APIs to mean what they say (a "vibe coder," or anyone new to
this codebase): `requestAudioFocus()` compiling, running, and even
appearing to work in a quick manual test is not evidence it's safe --
the fight only shows up once Chromium's *own* request is also active,
which every real page load triggers and a five-second smoke test
might not surface clearly.

## Applying it: the ownership test

Before adding any native code that touches a platform resource
(`AudioManager`, `SurfaceView`/decoder state, layout/inset
recalculation, orientation, theming, notifications), ask:

1. **Does Chromium's WebView already manage this resource for
   whatever's on the page?** If yes, this app does not *also* request
   or hold it. Mirror Chromium's resulting *state* if the platform
   needs to see it (a notification, a `MediaSessionCompat`), but
   never issue a second, independent claim on the resource itself.
2. **If this app must act, is the action idempotent / re-run-safe, or
   does it only work correctly exactly once per real state
   transition?** BUG-0001's theme-sync JS and the pre-fix
   `requestAudioFocus()` were both examples of the latter, fired on
   every repeated report instead of the edge. Guard on the edge, not
   the poll.
3. **Can this app's action be observed by Chromium as an
   interruption, prompting Chromium to "correct" it -- which this app
   would then observe as needing to react to, ad infinitum?** If
   step 1 and step 2 don't already rule this out, that loop is the
   bug, before a single line is written.

## Applied so far

- **BUG-0004 (audio focus):** fixed by removing this app's native
  `AudioFocusRequest` entirely. Chromium already owns the physical
  audio stream, already requests focus for it, and already
  pauses/resumes correctly on a genuine external interruption (a
  call, another app's playback). `MediaSessionCompat`/the
  notification only ever needed accurate *state*, which arrives for
  free via the JS bridge's real `play`/`pause`/`ended` events --
  holding a second, competing focus request bought this app nothing
  and cost it working Play/Pause. See
  `docs/bugs-caught/BUG-0004-audio-focus-ping-pong.md`.
- **BUG-0001 (decoder churn):** partially addressed by guarding
  theme-sync JS to only run on an actual theme *change* rather than
  every poll -- the same "edge, not poll" instinct as above. Root
  cause not yet fully closed; see the bug file.

## Non-goals

This agreement is not "never touch anything Chromium touches." Real
fullscreen, orientation lock, and native transport controls all
require this app to act on resources Chromium has *some* stake in --
that's the entire premise of `PROBLEM-STATEMENT.md`. The agreement is
narrower and non-negotiable: when this app acts, it must know
*specifically* what Chromium already does with that resource, and
either stay out of its way or take over cleanly -- never leave both
of you holding a claim on the same thing at once.
