# Moving from GNOME Kiosk to Cage

**Status:** Draft. Documentation only — no code in this branch has
switched compositors yet. This doc exists so the direction is written
down and staged *before* anything is rebuilt on it, the same discipline
`docs/foundational/STAGED-IMPLEMENTATION.md` already asks of every
other change here.
**Companion to:** `PROBLEM-STATEMENT.md`, `STAGED-IMPLEMENTATION.md`.

## Two mistakes, one root cause

This branch has made two decisions so far that both trace back to the
same instinct: reach for the GNOME piece that was already sitting
there, instead of the piece that actually matches what ARKtube is.

1. **The compositor.** GNOME Kiosk was chosen because it was the
   easiest path to "a minimal Wayland session with no shell chrome" on
   Ubuntu — it's in the archive, `gnome-kiosk-script-session` wires up
   GDM for free, and Stages 0–7 proved it can host ARKtube end to end.
   But GNOME Kiosk is still *GNOME's* kiosk compositor: it exists to
   run Mutter without the shell on top, which means it still carries
   Mutter, GSettings schemas, and a GNOME-shaped configuration surface
   ARKtube never asked for and mostly can't use (see
   `docs/STAGE-3-INPUT-MAPPING.md`'s own notes on GSettings keybinding
   schemas that don't apply to this session).
2. **The system overlay.** Stage 6 modeled the in-session system
   controls (volume, brightness, Wi-Fi, lock, power) on GNOME Shell's
   own top bar and quick-settings popover — small pill buttons, a
   clock, a calendar, panel-style icon buttons in a strip across the
   top. That's a desktop idiom, built for a mouse and a keyboard on a
   monitor at arm's length. ARKtube is a TV appliance, navigated with a
   remote or a controller from a couch. A GNOME Shell-shaped panel was
   the wrong reference point, full stop, independent of whether GNOME
   Kiosk sits underneath it.

Both mistakes have the same shape: pulling in GNOME's own visual and
architectural language by default, because it was convenient, rather
than because it was correct for this product. `docs/STAGE-8-TV-STYLE-
OVERLAY.md` fixes the second one now. This doc lays out fixing the
first — not yet in code, but staged, the same way Stage 8 was staged
before it landed.

## Why Cage

[Cage](https://github.com/cage-kiosk/cage) is a single-application
Wayland compositor built directly on wlroots. It does exactly one
thing: run one client, fullscreen, with nothing else. There is no
panel, no dock, no shell, no GSettings integration, no Mutter — there
is barely anything to configure at all, because there is barely
anything there.

That's a better match for `docs/foundational/PROBLEM-STATEMENT.md`'s
own stated goal than GNOME Kiosk ever was:

```text
Desired stack (unchanged by this migration):

Ubuntu
  └── ARKtube Session
       └── ARKtube
```

GNOME Kiosk gets close to this by *disabling* most of what Mutter can
do. Cage gets here by never having it in the first place. The
`PROBLEM-STATEMENT.md` line this migration takes most literally is its
own closing one: *"Its success is measured by how little it needs to
do, not by how many things it can accumulate."* A compositor whose
entire feature set is "run one program, fullscreen" accumulates
nothing by construction.

### What this trades away

Being honest about the trade, not just the upside:

* GNOME Kiosk 50's kiosk-hardening flags (`--enable-vt-switch` and
  friends, see the root README's own notes on this) go away — Cage has
  its own, much smaller, flag set instead. Stage 3's input-mapping
  verification (`docs/STAGE-3-INPUT-MAPPING.md`) will need to be
  redone against Cage, not assumed to carry over.
* `gnome-kiosk-script-session`'s existing GDM wiring (the `.desktop`
  entries, the gnome-session session file, the systemd user service —
  see `docs/STAGE-1-SELECTABLE-SESSION.md`) is a Ubuntu-packaged
  convenience this branch got mostly for free. Cage does not ship an
  equivalent package for Ubuntu/GDM today; Stage 1's work (the gear
  menu entry, the session `.desktop` file, the launch script) will need
  to be rebuilt by hand against Cage's actual CLI (`cage -- <command>`),
  not assumed to inherit GNOME Kiosk's package scaffolding.
* Everything Stages 2–7 already proved against GNOME Kiosk (session
  lifecycle, `KillMode=control-group` cleanup, the overlay's own launch
  point in `session/gnome-kiosk-script`) is currently *specific* to
  running inside a GNOME Kiosk session. None of it is assumed to be
  free under Cage; each one is a line item in the staged plan below,
  not a footnote.

None of this is a reason not to migrate — it's the actual size of the
migration, stated up front rather than discovered mid-way through.

## Responsibility boundary (updated)

The boundary from `PROBLEM-STATEMENT.md` doesn't change shape, only one
label in it:

```text
Ubuntu / GDM
       │
       │ session selection
       ▼
     Cage
       │
       │ session + compositor
       ▼
Webtop
       │
       │ launches/configures
       ▼
ARKtube
       │
       │ application behavior
       ▼
User
```

`Webtop` still means the same thing it always has: the thin layer
connecting these pieces, not a second window manager. The TV-style
system overlay from Stage 8 is part of that `Webtop` layer, launched
the same way it is today — forked into the session's own process
group before the compositor's client is exec'd — regardless of which
compositor sits above it. Cage does not need to know the overlay
exists any more than GNOME Kiosk did.

## Staged implementation

Same instinct as `STAGED-IMPLEMENTATION.md`: don't rebuild the whole
session on a guess, prove the layer underneath first. These stages
continue that document's numbering (Stage 8 already exists — the
TV-style overlay); this migration picks up after it and does not
begin until Stage 8 is done.

### Stage 9 — Feasibility

**Question:** Can Cage host ARKtube fullscreen at all, on the same
Ubuntu/Noble target this branch already runs on?

* Install `cage` (built from source if Noble's archive doesn't carry a
  recent enough build — confirm the packaged version first rather than
  assuming).
* Manually start Cage from a TTY pointed at the already-installed
  `arktube` binary, the same manual-first approach Stage 0 used for
  GNOME Kiosk.
* Confirm ARKtube launches, renders, and takes input with nothing else
  on screen.
* Confirm exiting cleanly returns control to the TTY.

**Exit condition:** matches Stage 0's own exit condition, against Cage
instead of GNOME Kiosk.

### Stage 10 — Selectable session, rebuilt for Cage

**Question:** Can ARKtube be chosen from GDM's gear icon, the same way
it can today, without `gnome-kiosk-script-session`'s existing package
scaffolding to lean on?

* Write the session `.desktop` file by hand, pointing at a launch
  script that runs `cage -- arktube` (or `cage -- <launch script>`, if
  the overlay and any future Cage-specific setup need a wrapper —
  compare against how `session/gnome-kiosk-script` does this today).
* Confirm the entry appears in the gear menu and the full cold-login
  flow works, matching Stage 1's own exit condition.

### Stage 11 — Session lifecycle, rebuilt for Cage

**Question:** Do lock, unlock, and logout still behave like a real
session, without `org.gnome.Kiosk.Script.service` (and its
`KillMode=control-group` override from Stage 4) to inherit?

* Confirm what process-group cleanup on logout looks like under Cage's
  own process model — Cage does not ship an equivalent systemd unit by
  default, so this may need a small systemd user unit of this branch's
  own, not GNOME Kiosk's borrowed one.
* Re-confirm lock/unlock/logout against that, matching Stage 2's exit
  condition.

### Stage 12 — Input mapping, re-verified for Cage

**Question:** Do ARKtube's keys/buttons still reach it unmolested,
under Cage's much smaller keybinding surface?

* Re-run Stage 3's own verification method (enumerate ARKtube's actual
  input needs, confirm nothing above it intercepts them) against Cage
  specifically — Cage's flag set is not GNOME Kiosk's, and nothing here
  should be assumed to carry over unchecked.

### Stage 13 — Overlay + hardening parity

**Question:** Does everything Stage 4 (hardening) and Stage 8 (the
TV-style overlay) already established still hold once Cage is the
compositor underneath them?

* Confirm the overlay's launch point (currently
  `session/gnome-kiosk-script`) has an equivalent hook in whatever
  Cage's own launch script becomes, and that it's still reaped on
  logout the same way.
* Re-confirm Stage 4's hardening checklist (no path out to a general
  desktop, no stray processes across login/logout cycles) against
  Cage.

### Stage 14 — Cutover and GNOME Kiosk removal

**Question:** Is Cage's session a strict, verified replacement, not
just an alternative sitting alongside GNOME Kiosk's?

* Once Stages 9–13 each independently pass, remove
  `gnome-kiosk`/`gnome-kiosk-script-session` from
  `session/install-webtop-session.sh` and the GNOME Kiosk-specific
  files it installs (`session/gnome-kiosk-script`,
  `session/systemd/org.gnome.Kiosk.Script.service.d/`), replacing them
  with their Cage equivalents rather than leaving both installed side
  by side.
* Update the root `README.md` and `docs/foundational/PROBLEM-
  STATEMENT.md` to describe Cage as the shipped compositor rather than
  GNOME Kiosk, and mark this doc's own status as complete.
* Log anything that broke along the way in `docs/bugs-caught/`, same
  as every stage before it.

**Exit condition:** A clean Ubuntu install, following only the
committed config and docs, reaches `Gear → ARKtube → ARKtube session`
under Cage, with GNOME Kiosk no longer installed as part of this
branch's own setup, and every Stage 0–8 guarantee (lifecycle, input,
hardening, the overlay) still holding.

## Non-goals of this migration

* This is not a rewrite of the TV-style overlay (Stage 8) or of
  ARKtube itself (`main`) — both stay exactly as they are; only the
  compositor underneath them changes.
* This does not add support for distributions other than Ubuntu/GDM —
  same non-goal `STAGED-IMPLEMENTATION.md` already states, unchanged.
* This does not attempt to run GNOME Kiosk and Cage side by side as a
  user-selectable choice. Stage 14 removes GNOME Kiosk once Cage is
  proven, rather than carrying both forward indefinitely.
