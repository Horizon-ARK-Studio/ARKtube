# System Design — Sway Compositor Layer

**Status:** Current. Describes the Sway-based design this branch is
built on as of `e05dd5c`, superseding the Cage-fork design described
historically in `PROBLEM_STATEMENT.md`.
**Companion to:** `PROBLEM_STATEMENT.md` (why), the root `README.md`
(quick reference), `webtop`'s `docs/foundational/PROBLEM-STATEMENT.md`
and `CAGE-MIGRATION.md` (the layer above this one).

## Layers

```text
Ubuntu / GDM
       │  session selection
       ▼
     Sway                    ← this document
       │  compositor, layer-shell, idle-inhibit, seat
       ▼
   Webtop                    ← webtop branch
       │  session lifecycle, launches ARKtube + overlay
       ▼
  ARKtube  +  Overlay        ← main branch (app) + Stage 8 overlay
       │  application behavior
       ▼
     User
```

Each layer owns exactly one job. This document is only about the
`Sway` layer: what it's configured to do, and why each piece of that
configuration exists.

## Responsibilities

Sway owns:

* the compositor itself — output, seat, and input handling
* placing ARKtube fullscreen, on every output, with no border and no
  gaps
* `wlr-layer-shell-v1`, so the TV-style overlay can place itself as a
  layer surface above ARKtube rather than as a second top-level window
* idle-inhibit, so the overlay or ARKtube can hold the session awake
  during playback
* session readiness signaling, via `sway-session.target`

Sway does not own:

* which application launches — that's Webtop's job
* what the overlay looks like or does — that's Stage 8's job
* application-level keyboard handling — that's ARKtube's own
  `user-script.js`

## Fullscreen placement

ARKtube is the only client Sway ever shows a border, gap, or
workspace-switch behavior for, because it's configured to have none of
those things:

```text
# /etc/sway/config.d/arktube.conf

exec arktube
for_window [app_id="arktube"] fullscreen global
default_border none
gaps inner 0
gaps outer 0
```

`fullscreen global` (rather than plain `fullscreen`) matters
specifically for multi-output setups: it covers every connected
display, rather than just the output ARKtube happened to open on.

## The overlay: why layer-shell, not a window

The TV-style system overlay (`webtop`'s `docs/STAGE-8-TV-STYLE-
OVERLAY.md`) needs to sit visually on top of ARKtube — network,
volume, and brightness controls, reachable without leaving the video
that's currently playing.

A second top-level window doesn't do this correctly on a tiling
compositor: it would either get tiled next to ARKtube, stack behind
it, or need to be fullscreened itself, stealing focus from playback
every time it opens.

`wlr-layer-shell-v1` is the correct protocol for this: it lets a
client request placement in a named layer (background, bottom, top,
overlay) independent of the normal window stack, which is exactly what
status bars, notification popups, and on-screen displays use it for
on every wlroots compositor.

```text
layer: overlay
       ▲
       │  drawn above all windows, incl. fullscreen ones
       │
layer: top          ← ARKtube would sit here if it weren't fullscreen-global
       │
layer: bottom
       │
layer: background
```

The earlier Cage fork (`layer_shell.c`, `idle_inhibit_v1.c`) hand-
implemented exactly this protocol, and proved it works — Stage 8's
`overlay.py` already targets `wlr-layer-shell-v1` and required no
changes to move onto Sway. Sway implements the protocol as a
maintained, first-class feature, so this branch no longer implements
or maintains any part of it. The overlay's behavior is unchanged; what
changed is who's responsible for the protocol underneath it.

## Idle-inhibit

The overlay or ARKtube can hold the session awake during active
playback using the standard `wlr-idle-inhibit-manager-v1` protocol,
which Sway also implements natively. This replaces the fork's
`idle_inhibit_v1.c`, with the same behavior: the inhibitor is held
while a matching condition (fullscreen, focus, or explicit open) is
true, and released automatically when it isn't.

## Session integration

Sway does not ship its own systemd unit, so the session defines one at
the point Sway signals it's ready — the last lines of the compositor
config:

```text
exec systemctl --user import-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP
exec systemctl --user start sway-session.target
```

`sway-session.target` is what the rest of the session — lock, power,
network handling on the `webtop` side — binds to. Stopping that target
is the signal that the session is over; nothing here re-implements
GNOME Kiosk's `KillMode=control-group` cleanup, because Sway's own
process model already terminates its one client when it exits.

## Failure modes

### `wlr-layer-shell-v1` surface fails to place correctly

Fallback:

```text
Confirm the overlay is requesting a named layer (not falling back to
a default), and that Sway's version supports the requested anchor/
exclusive-zone combination.
```

Do not reintroduce a hand-rolled layer-shell implementation to work
around a placement bug — file it against Sway or the overlay's
request, not this layer.

### Idle-inhibit doesn't hold during playback

Fallback:

```text
Confirm the inhibitor is actually being requested (by the overlay or
by ARKtube) at the moment playback starts, rather than assuming Sway
is silently ignoring a correctly-requested inhibitor.
```

### A future protocol ARKtube needs isn't implemented by Sway

This is the one scenario that would revisit this document's decision.
If that happens, the evaluation should weigh the same trade
`PROBLEM_STATEMENT.md` already weighed — a config-level workaround on
Sway vs. a maintenance burden in a fork — rather than defaulting back
to custom C.

## Success criteria

This layer is correct when all of the following hold:

* ARKtube launches fullscreen, on every output, with no border or gap.
* The overlay places itself above ARKtube via `wlr-layer-shell-v1`,
  with no changes required to `overlay.py` itself.
* Idle-inhibit holds the session awake during active playback and
  releases it otherwise.
* `sway-session.target` reaches "started" once Sway is ready, and
  stops cleanly on logout.
* None of the above requires compositor-level C code maintained by
  this project.

## Non-goals

* A general-purpose Sway configuration — see the root `README.md`'s
  own "What this is not" section.
* Re-implementing anything Sway already provides.
* Session-selection (GDM/gear-menu) integration — `webtop`'s territory,
  not this branch's.
