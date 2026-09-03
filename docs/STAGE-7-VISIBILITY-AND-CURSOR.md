# Stage 7 — Topbar visibility (Immersive Mode / offline) and cursor auto-hide

**Status:** Implemented at the code level. Same on-hardware caveat as
every prior stage (see "What was not verified" below) — no display
manager in this environment.
**Stage definition:** added below to `docs/foundational/STAGED-IMPLEMENTATION.md`,
this doc is its implementation record, same pairing every stage above
uses.
**Built on:** Stage 6's topbar (`session/topbar/topbar.py`) and its own
launch point in `session/gnome-kiosk-script`; `main`'s Immersive Mode
(`docs/bugs-caught/IMMERSIVE-MODE.md` on the `main` branch).

## Why this stage exists

Stage 6 made the topbar always-on-top and always visible — a reasonable
default, but one with two real gaps once `main`'s Immersive Mode
(`ARKtube/resources/js/app-init.js`) is in the picture:

1. Immersive Mode's whole point is a locked-down, fullscreen, nothing-
   else-reachable view. A topbar strip sitting on top of that — even a
   thin 32px one — undercuts it. But the topbar is also the *only*
   place `docs/STAGE-6-OFFLINE-TOPBAR.md` built lock/logout/power/Wi-Fi
   controls into this session, so it can't simply disappear for good
   once Immersive Mode is on: the moment Immersive Mode is turned back
   off, those controls need to be reachable again, immediately, not
   after some unrelated action.
2. Stage 6's own hard requirement was "every control must work with no
   network" — but that requirement is only met if the topbar itself is
   *visible* when there's no network. A topbar that's hidden because
   Immersive Mode happens to be on, on a machine that has just lost its
   only connection, is exactly the scenario Stage 6 was built to cover
   and would now fail.

Both gaps have the same shape: the bar should hide only when hiding it
costs nothing, and it must fail toward being visible whenever that's
not clearly true.

Separately, `docs/foundational/PROBLEM-STATEMENT.md`'s kiosk framing
also implies a system-wide, ordinary kiosk expectation Stage 6 didn't
address at all: an idle mouse pointer sitting in the middle of a video
is a defect in any kiosk/TV-style deployment, not a Webtop-specific one.
That's this stage's third, unrelated-to-the-topbar fix.

## What was built

### 1. The topbar hides when it can, and only when it can

`session/topbar/topbar.py` gained a background thread,
`_visibility_watcher()`, started from `main()` right before the
blocking `webview.start()` call hands the process over to the GTK main
loop. Once a second, it re-evaluates:

```
should_show = (not immersive_mode_enabled) or (not internet_available)
```

and calls `window.show()` / `window.hide()` only when that value
actually changes — not every tick, and not by tearing the window down
and recreating it.

* **Immersive Mode** is read from the exact file `main`'s own
  `app-init.js` and its packaging launchers (`build-deb.sh`, `AppRun`)
  already use as the single source of truth:
  `${XDG_DATA_HOME:-$HOME/.local/share}/ARKtube/.storage/immersiveMode.neustorage`,
  a plain `"1"` or `"0"`. `_immersive_mode_enabled()` in `topbar.py`
  reads it directly — no IPC, no new file, no coordination needed with
  `main` beyond the file both sides already agree on. Missing/unreadable
  fails open to `False` ("not immersive"), the same direction
  `app-init.js`'s own `loadImmersiveModePreference()` already fails in.
* **Connectivity** is read via `nmcli networking connectivity` (no
  `check` — see the docstring on `_internet_available()` for why the
  cached read, not a forced probe, is what a once-a-second poll can
  afford). Only an exact `"full"` counts as online; `nmcli` missing, or
  anything else NetworkManager reports (`limited`, `portal`, `none`,
  `unknown`), counts as *not* online, which — combined with the `or`
  above — means the bar shows. This mirrors Stage 6's own
  `_wifi()`/`_airplane_mode()` calls, which already treat a missing
  `nmcli` as "control unavailable" rather than erroring.

Right before hiding, the watcher calls `set_panel("none")` (shrinking
the window back to bar height) and `evaluate_js()`'s a small JS hook,
`window.__arktubeTopbarCollapse` (added to `static/app.js`), so a panel
left open never resumes open the next time the bar reappears.

Every step of this — reading a file, running `nmcli`, resizing/hiding a
`webview.Window` — is local. Nothing added here makes a network request
of its own; the one thing that *reads* network state was already being
read the same way for the Wi-Fi tile before this stage existed.

### 2. Cursor auto-hide, on the X11 session only

`session/gnome-kiosk-script` now also forks `unclutter-xfixes --timeout
10` (falling back to the older `unclutter -idle 10 -root` if only that
package is installed) into the background, immediately before `exec
arktube` — the exact same placement, and for the exact same reason, the
topbar itself already uses: forked *before* the `exec` replaces this
script's process image, it lands in `org.gnome.Kiosk.Script.service`'s
own cgroup, so Stage 4's `KillMode=control-group` reaps it on logout
along with everything else, with no third lifecycle to maintain.

This is now gated on `[ "${XDG_SESSION_TYPE:-}" = x11 ]`. It wasn't in
the version first landed on this branch, which forked the same command
unconditionally under both `wayland-sessions/arktube.desktop` (the
primary path) and `xsessions/arktube.desktop` (the secondary Xorg one).
`unclutter`/`unclutter-xfixes` both work by talking to the X server's
XFixes extension; on the Wayland session there is no system-wide X
server for them to attach to, so the unconditional version mostly just
forked a process that sat there doing nothing on the session this
branch is actually built for — a real bug, not a documented gap, since
the code claimed to do something it structurally couldn't. See "What
was not verified" for the rest of the story, and why there isn't yet a
Wayland-side equivalent to gate it toward instead.

### Installer changes

`session/install-webtop-session.sh` now also `apt-get install`s
`unclutter-xfixes`. Nothing else needs installing for the visibility
half of this stage — it's pure logic added to `topbar.py`, which Stage
6 already deploys.

## Why the bar doesn't just move (it shows/hides)

An earlier option considered was sliding the bar off the top edge
(`window.move(0, -BAR_HEIGHT)`) instead of `hide()`/`show()`, on the
theory that a slide reads as less abrupt. Two things ruled it out:

* `on_top=True` + `transparent=True` windows in pywebview's GTK backend
  are not guaranteed to composite correctly once positioned fully
  off-screen on every compositor — an untested assumption Stage 6's own
  "Window model" section already flagged as unverified for the
  *on-screen* case, and off-screen positioning adds a second unverified
  assumption on top of it.
* `hide()`/`show()` map onto exactly what this state actually is: the
  bar is not currently something the user should be able to interact
  with at all while Immersive Mode is genuinely locking the session
  down. A window sitting one pixel off-screen is still a window;
  `hide()` withdraws it outright, which is the correct semantics for
  "should not be reachable right now."

## What was verified, and how

* `_immersive_mode_enabled()`'s file path and value format were
  confirmed against `main`'s own `app-init.js`
  (`Neutralino.storage.setData(IMMERSIVE_STORAGE_KEY, enabled ? "1" :
  "0")`) and `packaging/linux/build-deb.sh`'s launcher, which already
  reads the identical path — not guessed at independently.
* `nmcli networking connectivity`'s output values (`full`, `limited`,
  `portal`, `none`, `unknown`) were checked against NetworkManager's own
  documented connectivity states, the same way Stage 6 checked
  `wpctl`/`nmcli`/`upower`'s output shapes before relying on them.
* `unclutter-xfixes`'s `--timeout` flag (seconds of inactivity before
  hiding, default 5, set to 10 here) and its `--idle`-as-alias-for-
  `--timeout` compatibility shim for the older `unclutter` package name
  were confirmed against the tool's own manual page, not assumed from
  the name alone.

What was **not** verified, stated plainly rather than assumed, the same
way every prior stage in this branch has:

* **On-hardware click-through** — whether `window.hide()`/`show()`
  actually behaves instantaneously and without visual glitching on
  pywebview's GTK/WebKit backend, whether a real Immersive Mode toggle
  in a running `main` session is picked up by the watcher within the
  1-second poll window in practice, and whether `nmcli networking
  connectivity`'s cached value updates quickly enough after a real
  cable pull or Wi-Fi disconnect to feel "automatic" rather than
  delayed. None of this could be exercised end-to-end here — no display
  manager, no NetworkManager, no real network hardware in this
  container, the same limitation every stage above has carried.
* **Wayland compositors** — `unclutter-xfixes` is an X11/XFixes tool.
  On the `xsessions/arktube.desktop` entry (Xorg) this should work as
  documented; on `wayland-sessions/arktube.desktop` there is no
  system-wide X server for it to attach to at all, so it's now gated
  off on that session via `$XDG_SESSION_TYPE` rather than started and
  left doing nothing.

  There also isn't a compositor-level fix to gate it toward instead,
  at least not on the GNOME Kiosk this branch targets: `--no-cursor`,
  the flag that would drive Mutter/GNOME Kiosk's own idle-cursor
  behavior directly, was only added in GNOME Kiosk 50. Ubuntu 24.04
  Noble ships `46.0-1build2` (see the root README's "Session model"
  section — the same version gap already documented there for
  `--enable-vt-switch`), which predates that flag entirely.

  The deeper reason this can't just be worked around with another
  background daemon, on 46 or 50: under Wayland the cursor image is
  drawn by whichever client currently owns the surface under the
  pointer, not by a bystander process watching for idle input. A
  background daemon structurally cannot reach in and hide another
  app's cursor — that's Wayland's client-isolation model working as
  intended, not a gap in `unclutter-xfixes` specifically. A real fix on
  Wayland means the cursor-hide happening inside ARKtube's own webview
  (e.g. CSS `cursor: none` after idle, driven by `app-init.js`), which
  is `main`'s responsibility per the root README's ARKtube/Webtop
  split, not something addressable from this session layer. Flagged
  here as an open item for `main`, rather than left as something this
  stage silently only half-does.
* **`nmcli`-less machines** — a machine with NetworkManager fully absent
  (not just Wi-Fi off) falls back to "not online" per this stage's own
  fail-open rule, which means the bar stays permanently visible there.
  Correct per the stated rule, but not the same as *confirming*
  connectivity is actually down — worth knowing if `nmcli` is ever
  intentionally left off a target image for another reason.

## Exit condition assessment

* Immersive Mode off, or the machine online → topbar reachable: met at
  the code level — `_visibility_watcher()`'s `should_show` expression
  is exactly that condition, and both inputs were confirmed against
  their real sources rather than assumed.
- Immersive Mode on and the machine confirmed online → topbar hidden:
  met at the code level, with the same on-hardware caveat as everything
  else in this branch.
* Mouse pointer hidden after 10s idle: met on X11
  (`xsessions/arktube.desktop`); explicitly **not** met on the primary
  Wayland session (`wayland-sessions/arktube.desktop`) — now gated off
  there rather than started and doing nothing, and not fixable from
  this branch alone since it needs an `main`-side change (see "What
  was not verified" above).

## Files

* `session/topbar/topbar.py` — `_arktube_data_dir()`,
  `_immersive_mode_enabled()`, `_internet_available()`,
  `_visibility_watcher()` added; `main()` now starts the watcher thread
  before `webview.start()`.
* `session/topbar/static/app.js` — `window.__arktubeTopbarCollapse`
  hook added for the watcher's `evaluate_js()` call.
* `session/gnome-kiosk-script` — forks `unclutter-xfixes`
  (or `unclutter`) before `exec arktube`, gated on
  `$XDG_SESSION_TYPE = x11`.
* `session/install-webtop-session.sh` — installs `unclutter-xfixes`;
  summary output at the end mentions both new behaviors and their
  X11-only scope for the cursor half.
* `docs/STAGE-7-VISIBILITY-AND-CURSOR.md` — this file.
* `docs/README.md` — added a row for this stage.
* `docs/foundational/STAGED-IMPLEMENTATION.md` — added Stage 7's
  definition, which — like Stage 6 before it — wasn't in the original
  plan.
