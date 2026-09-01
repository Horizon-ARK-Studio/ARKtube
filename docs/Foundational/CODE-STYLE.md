# ARKtube -- How We Write Code

**Status:** Living document
**Scope:** `android-project/` (the principles generalize; the examples are Kotlin/Android)

This is not a style guide about brace placement. It's a reference for
the structural decisions this codebase keeps making on purpose, so
the next change follows the same shape instead of drifting back
toward a single file that does everything -- which is exactly the
state `MainActivity.kt` grew into before the refactor this document
follows.

---

## 1. One File, One Reason to Change

The rule that actually matters: **a class exists because one thing
in the app can change independently of everything else**, not
because it was convenient to keep adding to the file that was
already open.

Before the refactor, `MainActivity.kt` owned WebView setup, three JS
bridges, fullscreen video, zoom-crop math, immersive-mode bars,
orientation locking, status-bar theming, rotation reflow, the
stretch-to-fill button and its persisted preference, and media-session
binding -- in one file, in one class, at ~1,700 lines. Every one of
those is a legitimate reason to change the app. None of them are
reasons to change any of the *others*. That's the smell: when a
change to theming risks a regression in fullscreen video because they
live in the same class, the file's boundaries don't match the
problem's boundaries.

The fix isn't "smaller files" as a goal in itself -- it's giving each
concern a package that matches what it's actually responsible for:

```
com.arktube.app
├── MainActivity.kt          -- Activity lifecycle + wiring only
├── MediaPlaybackService.kt
├── fullscreen/               -- everything about fullscreen video
├── webview/                  -- WebView construction + JS bridges
│   └── bridge/
├── media/                     -- media-session coordination
├── theme/                     -- status bar theming
├── layout/                    -- rotation/reflow
├── prefs/                     -- persisted preferences
└── logging/                   -- ArkLogger
```

`MainActivity` after the split does one job: own the Activity
lifecycle and wire its collaborators together. If you're adding a
feature and reaching for `MainActivity.kt`, stop and ask which
existing package it belongs to first -- or whether it's a new package.
It very rarely needs to be MainActivity itself.

---

## 2. Reach for a GoF Pattern When It Names a Real Constraint, Not by Default

This codebase uses a handful of classic Gang-of-Four patterns, but
none of them were applied because "Kotlin is OO so we should." Each
one is here because it's the accurate name for a constraint the code
already had:

- **Singleton** (`logging.ArkLogger`) -- there is exactly one failure
  log for the whole process, and every component needs to reach it
  without a Context/DI reference threaded through every constructor.
  A Kotlin `object` is the honest way to say "exactly one, globally
  reachable."
- **Factory** (`webview.ArkTubeWebViewFactory`,
  `fullscreen.StretchToggleButtonFactory`) -- assembling a fully
  configured `WebView` (settings + three bridges + script injection)
  or a styled `Button` is multi-step and easy to get subtly wrong if
  it's inlined at every call site. A factory makes "the one correct
  way to build this thing" a single function, and callers never touch
  the raw constructor.
- **Facade** (`fullscreen.FullscreenVideoController`) -- fullscreen
  video is a genuinely tangled subsystem (customView hosting,
  SurfaceView z-order, zoom-crop, immersive bars, orientation lock).
  Nothing outside fullscreen needs to know those pieces exist
  separately; the Facade gives `MainActivity` one seam
  (`enterFullscreen()`/`exitFullscreen()`-shaped calls) instead of
  five.
- **Strategy** (`fullscreen.ZoomCropStrategy`) -- the crop math is one
  *replaceable* decision inside the fullscreen Facade (a future build
  variant might not want crop-to-fill at all). Pulling it behind an
  interface means that decision can change without touching the
  Facade's view-hosting logic.
- **Abstract Class / Template Method** (`webview.bridge.ArkTubeJsBridge`)
  -- every `@JavascriptInterface` bridge method needs identical
  try/catch/finally logging around it (see Section 3), because an
  uncaught exception on WebView's JS thread fails silently instead of
  crashing loudly. The abstract base owns that shared shape once;
  each concrete bridge (`ThemeBridge`, `OrientationBridge`,
  `MediaPlaybackBridge`) supplies only its own one-line method bodies.

The test before introducing a pattern: **can you name the specific
thing that varies, or the specific constraint the pattern is solving,
in one sentence?** If not, it's probably decoration, not design --
plain functions and a normal class are usually the right call, and
most of this codebase is still just that.

---

## 3. Every Boundary That Can Fail Silently Gets try/catch/finally + Logging

Three call boundaries in this app fail in ways that don't show up as
a normal crash:

1. **`@JavascriptInterface` methods** -- an uncaught exception on
   WebView's JS thread aborts that one call silently; the page just
   doesn't get a response.
2. **File/service I/O** (the failure-log write itself, service
   binding) -- can fail for reasons entirely outside app logic
   (storage, OS scheduling) and shouldn't be allowed to cascade.
3. **Anything invoked from a lifecycle callback that Android itself
   calls** (`onCreate`, `onConfigurationChanged`, service callbacks)
   -- there's no caller to hand a thrown exception back to in a way
   that's useful.

The convention at each of these boundaries:

```kotlin
fun doSomething(component: String) {
    ArkLogger.d(component, "doSomething: start")
    try {
        // the actual work
    } catch (t: Throwable) {
        ArkLogger.e(component, "doSomething: failed", t)
        // handle or rethrow -- never swallow silently
    } finally {
        ArkLogger.d(component, "doSomething: end")
    }
}
```

`ArkLogger.track(component, operation) { block }` wraps exactly this
shape for the common case (log start, log success/failure, log end,
rethrow) -- reach for it first before hand-writing the same
try/catch/finally again. `ArkTubeJsBridge.safeCall(methodName) { }`
is the bridge-specific variant that swallows instead of rethrowing,
since a JS bridge call has no caller that could do anything with a
rethrown exception anyway.

`ArkLogger` itself mirrors every `w()`/`e()` call to an on-device file
at `context.filesDir/--log-failed` (internal storage -- no permission
needed, wiped on uninstall), in addition to Logcat, specifically so a
failure can be pulled off a real device after the fact:

```
adb shell run-as com.arktube.app cat files/--log-failed
```

Not every function needs this. Pure computation (`ZoomCropStrategy.compute`,
`CssColorParser`) doesn't call across a boundary that can fail
silently, and wrapping it in try/catch/finally would just be noise
that hides a bug that *should* crash loudly in development. Reserve
the pattern for the three boundary kinds above.

---

## 4. Constructor Injection Over Reaching for Globals

Collaborators are handed what they need at construction time
(`FullscreenVideoController(activity, rootLayout, ...)`,
`MediaSessionCoordinator(activity, webViewProvider)`) rather than
looking up `MainActivity` or a global singleton for it. `ArkLogger` is
the deliberate exception -- see Section 2's Singleton entry for why a
process-wide logger specifically earns global access. Everything else
should be constructible and testable without an Activity already
running.

---

## 5. Doc Comments Explain *Why*, Not *What*

A doc comment that restates the method signature in prose is dead
weight. The ones worth writing explain the non-obvious reason
something is built the way it is -- the bug it was fixing, the
platform behavior it's working around, the constraint that ruled out
the simpler version. `FullscreenVideoController`'s class doc and
`ZoomCropStrategy`'s are the reference examples: both explain the
underlying platform behavior (Chromium's customView is a separate
native `SurfaceView` outside the DOM) that makes the *obvious* fix
(a CSS transform) not work, before describing what the code does
instead.

When a comment block grows past explaining one class's own behavior
and starts narrating the whole app, that's a sign it belongs in
`docs/Foundational/` instead -- see `PROBLEM-STATEMENT.md` for the
project-level version of this same instinct.

---

## 6. When You're Not Sure Where Something Goes

1. Does it change for a reason nothing else in the app changes for?
   New package (or an existing one that already owns that reason).
2. Does it cross a boundary that fails silently (JS bridge, I/O,
   platform lifecycle callback)? Wrap it per Section 3.
3. Are you about to add a second implementation of something that
   already exists, or hard-code a decision a future variant might
   need to swap? Consider Strategy/Factory -- but only if you can
   state the varying thing in one sentence (Section 2).
4. Otherwise: a plain function or a small class, no pattern name
   required. Most of this codebase is that, and it should stay that
   way.
