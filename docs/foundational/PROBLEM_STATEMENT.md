# The Compositor Problem

## Context

`webtop`'s own `docs/foundational/PROBLEM-STATEMENT.md` already
establishes the goal one layer up: ARKtube should be selectable as a
session from Ubuntu's login screen, and that session should be a
lightweight kiosk compositor rather than the full GNOME Shell desktop.

That doc names GNOME Kiosk as the compositor. It was wrong.

`docs/foundational/CAGE-MIGRATION.md`, also on `webtop`, corrects
course toward [Cage](https://github.com/cage-kiosk/cage) — a
single-application Wayland compositor built on wlroots — and stages
the migration across several implementation steps.

This branch, `arktube-layer-shell`, is where that migration was
actually attempted. It is the compositor-layer implementation, not the
session-integration layer. Its job is narrower than either of those
two docs: **host ARKtube fullscreen, and give the TV-style system
overlay (`docs/STAGE-8-TV-STYLE-OVERLAY.md` on `webtop`) a real
surface to draw itself onto.**

## The problem

Two things need to be true of whatever sits under ARKtube:

```text
1. It shows exactly one application, fullscreen, with nothing else.
2. It gives the system overlay a way to draw itself *on top of*
   that application, without being a second top-level window that
   steals focus or gets tiled next to it.
```

(1) is what Cage already does. (2) is not — Cage, out of the box, has
no `wlr-layer-shell-v1` support, and the overlay was already using
`wlr-layer-shell-v1` (Stage 8's `overlay.py`) to position itself as a
layer surface rather than a normal window, the same way a status bar
or notification popup does on a wlroots compositor.

That gap is what this branch exists to close.

## What was tried

### Cage + a hand-written layer-shell implementation

The first approach followed `CAGE-MIGRATION.md`'s plan literally:
take Cage's C source, and add the missing protocol support directly.

That produced most of this branch's early history:

* `layer_shell.c` / `layer_shell.h` — a `wlr_layer_shell_v1`
  implementation bolted onto Cage's existing `wlr_scene` tree
* `idle_inhibit_v1.c` / `idle_inhibit_v1.h` — idle-inhibit support, so
  the overlay (or ARKtube itself, during playback) can hold the
  session awake
* `3cd4752` — "Add wlr-layer-shell-v1 support"
* `bc6c3ca` — "overlay.py: real layer-shell placement, a lock that
  locks, fixed logout"

This worked. The overlay got real layer-shell placement, locking
worked, and logout no longer hung. It's a legitimate proof that the
approach `CAGE-MIGRATION.md` proposed is technically sound.

### Why it was abandoned anyway

Maintaining a `wlr_layer_shell_v1` implementation, an idle-inhibit
implementation, and everything else a real compositor needs — output
management, seat handling, XDG-shell, XWayland — as a hand-patched
fork of a 700-line C kiosk compositor is a growing maintenance surface
with no natural ceiling. Every wlroots API change, every protocol this
branch didn't yet implement, every memory-safety bug (`35c56bf` —
"Fixed a use after free") is now this branch's problem to own, in C,
indefinitely.

That cost buys nothing ARKtube actually needs. The goal was never "run
a custom compositor." It was "host one application and one overlay,
correctly." A compositor that already implements every protocol this
branch was reimplementing gets there with zero of that maintenance
burden.

## The actual decision

> **I don't have time to fight an uphill battle with C and its
> ecosystem. So Cage will be replaced with Sway from now on.**
> — `e05dd5c`

[Sway](https://github.com/swaywm/sway) is also built on wlroots. It
already implements `wlr-layer-shell-v1`, idle-inhibit, XDG-shell,
XWayland, and output/seat handling as first-class, maintained
features — the exact set this branch was rebuilding by hand.

The trade is the mirror image of the one `CAGE-MIGRATION.md` made
against GNOME Kiosk: Sway is a general tiling compositor, not a
single-purpose kiosk one, so it carries workspaces, tiling, and a
config surface ARKtube doesn't need — the same complaint that moved
this branch off GNOME Kiosk in the first place.

The difference is what that surface costs. GNOME Kiosk's surface was
Mutter and GSettings schemas underneath a compositor still being
actively stripped down. Sway's surface is a config file:

```text
for_window [app_id="arktube"] fullscreen global
default_border none
gaps inner 0
gaps outer 0
```

Four lines turn a general compositor into a de facto single-app one.
Zero lines of C are required to get `wlr-layer-shell-v1`, because Sway
already has it. That asymmetry — config vs. protocol implementation —
is the whole decision.

## What this branch owns now

```text
Ubuntu / GDM
       │
       │ session selection
       ▼
     Sway
       │
       │ session + compositor + layer-shell + idle-inhibit
       ▼
   Webtop
       │
       │ launches/configures
       ▼
  ARKtube  +  TV-style overlay (layer surface)
       │
       ▼
     User
```

This branch's own scope is exactly the `Sway` box: the compositor
configuration, and confirming everything Cage's fork proved by hand —
fullscreen placement, layer-shell overlay placement, lock, logout,
idle-inhibit — still holds under Sway instead. See
`SYSTEM_DESIGN.md` for how that's structured.

## Non-goals

* Rebuilding or maintaining a custom compositor. That's the thing this
  branch specifically stopped doing.
* Re-litigating GNOME Kiosk vs. Cage vs. Sway beyond what's written
  here and in `webtop`'s `CAGE-MIGRATION.md` — the decision is made,
  and future compositor changes should get their own doc, not edits to
  this history.
* Session-selection integration (GDM `.desktop` entries, the gear
  menu) — that's `webtop`'s `docs/STAGE-1-SELECTABLE-SESSION.md`
  territory, rebuilt for Sway where it needs to be, not duplicated
  here.
* ARKtube's own application behavior — unchanged, and out of scope
  here regardless of which compositor is underneath it.

## One-sentence definition

> **This branch provides the Wayland compositor layer — one
> fullscreen application, one layer-shell overlay, correct lock/idle
> behavior — using Sway instead of a hand-maintained Cage fork,
> because Sway already implements the protocols that fork was
> rebuilding by hand.**
