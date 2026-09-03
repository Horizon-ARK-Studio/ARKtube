# Stage 5 — Documentation and handoff

**Status:** Implemented, within what this stage can actually verify. The
committed config was cross-checked against every prior stage doc's own
claims and against `session/install-webtop-session.sh`'s actual behavior;
two places where the root `README.md` stated something true of GNOME
Kiosk in general but not of the exact package this branch installs were
corrected in place, matching the "read the actual shipped thing" method
Stages 1–4 already used. `docs/bugs-caught/` stays empty — nothing here
was found broken, and this stage does not manufacture an entry to fill
the log.
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 5.
**Built on:** `docs/STAGE-1-SELECTABLE-SESSION.md` through
`docs/STAGE-4-HARDENING.md` — this stage doesn't add new session
behavior, it checks that everything those stages shipped is both
recorded in committed files (not just prose) and accurately described by
the root README.

## What this stage actually checks

Stage 5's question is narrower than it sounds: not "is Webtop finished,"
but "could someone else reproduce this setup from the docs alone,"
per its own exit condition. That splits into three concrete checks.

### 1. Is everything needed to reproduce this actually committed, not just described?

Every artifact `session/install-webtop-session.sh` installs already lives
under version control, not just in stage-doc prose:

| What gets installed | Committed source |
|---|---|
| Gear-menu entry (Wayland) | `session/wayland-sessions/arktube.desktop` |
| Gear-menu entry (X11) | `session/xsessions/arktube.desktop` |
| The kiosk's managed script | `session/gnome-kiosk-script` |
| Restart-loop / `KillMode` fix | `session/systemd/org.gnome.Kiosk.Script.service.d/override.conf` |
| The installer itself | `session/install-webtop-session.sh` |

Nothing this branch depends on exists only as a manual step someone would
have to reconstruct from a stage doc's description — the install script
is the single entry point, and every file it copies is present in the
tree it runs from. That was true before this stage; this stage's
contribution is confirming it by re-reading the installer against the
directory listing rather than assuming it, and recording that check here
rather than only in a commit message.

### 2. Does `docs/bugs-caught/` reflect reality?

Its own `README.md` already states plainly that it's empty and explains
the format to use once there's something to log. That remains accurate:
no session/kiosk-specific bug was found while producing Stages 0–5 in
this environment beyond the version-gap findings Stages 1–3 already
documented as corrections (gear-menu naming, the restart-loop default,
the `--enable-vt-switch` line) — those are documentation corrections
about a *different* GNOME Kiosk version's behavior, not bugs in what
this branch ships, so they belong where they already are rather than
duplicated into a `BUG-XXXX` entry. Filling this log with something
invented to make Stage 5 look more complete would make it less useful
the first time a real bug needs to go in it.

### 3. Does the root README still match what was actually built?

Checked line by line against the four prior stage docs' own findings.
Two places didn't match, both already flagged as corrections inside
Stage 3 and Stage 2's docs but never carried back into the README text
itself:

* **The `--enable-vt-switch` line.** Stage 3 confirmed this describes
  GNOME Kiosk 50; Noble's `46.0-1build2` — the version this branch
  installs — has no such flag and no VT-switch neutering for it to
  restore, confirmed directly from that binary's `--help-all`. Stage 3's
  own doc noted the gap but deliberately left the README unedited,
  since changing session behavior wasn't Stage 3's job. Documentation
  accuracy for handoff *is* this stage's job, so a footnote was added to
  the README in place, pointing at `docs/STAGE-3-INPUT-MAPPING.md` for
  the full verification rather than duplicating it.
* **The "GNOME Kiosk 50 changed... so a user can log out" line.** True
  of GNOME Kiosk 50 upstream, and Stage 2 already found Noble's shipped
  `46.0-1build2` predates that fix and ships `Restart=always` unmodified
  — which is exactly why `override.conf` exists. The README described
  the upstream fix without saying this branch is reproducing it for an
  older package, which reads as if the fix were already present
  upstream on the target platform. A footnote was added pointing at
  `docs/STAGE-2-SESSION-LIFECYCLE.md` and naming the drop-in that does
  the actual work here.

No other drift was found: the session-model diagram, the responsibility
boundaries (GDM / GNOME Kiosk / Webtop / ARKtube), the window-policy JSON
snippet, and the production-expectations checklist all still match what
Stages 1–4 verified or shipped.

## Exit condition assessment

Stage 5's exit condition is: *"A clean Ubuntu install, following only the
committed config and docs, reaches `Gear → ARKtube → ARKtube session`
with no undocumented manual steps."*

What's confirmed: every file the installer needs is committed, the
installer itself needs no undocumented input beyond ARKtube already being
installed as a `.deb` (which it already checks for and warns about, not
silently assumes), and the docs now describe the exact package version
this branch targets rather than GNOME Kiosk in general where the two
diverge.

What's still open, and stated plainly rather than assumed away: this has
never been run start-to-finish against a real GDM login screen in this
environment, for the same reason every prior stage flagged it — no
display manager here. "No undocumented manual steps" is verified by
inspection (nothing in the installer requires a step that isn't either
automated or explicitly printed to the user, per the installer's own
closing message), not by having actually clicked through a cold login on
hardware. That on-hardware click-through is the one item every stage
from 1 onward has carried forward unresolved, and Stage 5 doesn't close
it — documentation can't verify a login screen it can't reach.

## What's genuinely unverified

* On-hardware click-through, end to end, from a cold GDM login screen —
  unchanged from every prior stage. This is the actual exit condition
  for the branch as a whole, not just this stage, and nothing in a
  container without a display manager can close it.
* Whether the two README footnotes added here are sufficient for someone
  unfamiliar with the branch to not be misled, versus needing the fuller
  stage docs they point to — a documentation-clarity judgment, not
  something that can be confirmed mechanically the way the version
  checks above were.

## Files

* `README.md` — added two footnotes correcting version-specific claims
  (VT-switch, restart-on-exit) to say plainly that they describe GNOME
  Kiosk 50 while this branch targets and reproduces the equivalent
  behavior for Noble's older `46.0-1build2`, with pointers to the stage
  docs that did the underlying verification.
* `docs/STAGE-5-DOCUMENTATION-HANDOFF.md` — this file.
* `docs/README.md` — added a row for this stage.
* `docs/bugs-caught/` — unchanged; confirmed still accurately empty
  rather than backfilled.
