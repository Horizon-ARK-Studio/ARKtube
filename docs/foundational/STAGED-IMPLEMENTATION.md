# Staged Implementation

**Status:** Draft
**Companion to:** `PROBLEM-STATEMENT.md`

`PROBLEM-STATEMENT.md` defines what Webtop is for. This document breaks
that into stages, in the order they should actually be built and verified.
Each stage should be provably working before the next one starts — this
branch does not benefit from getting ahead of itself.

The shape follows the same instinct as `main`'s own phased rollout: don't
build the whole thing on a guess, prove the layer underneath first.

---

## Stage 0 — Feasibility

**Question:** Can GNOME Kiosk host ARKtube as a session at all, launched
outside the normal GNOME Shell desktop?

Steps:

* Install GNOME Kiosk alongside the existing Ubuntu/GDM setup.
* Manually start GNOME Kiosk from a TTY, pointed at the already-installed
  ARKtube binary, with no session file involved yet.
* Confirm ARKtube launches, renders, and takes input with no shell chrome
  around it.
* Confirm exiting ARKtube (or the compositor) returns control cleanly to
  the TTY, not a dead session.

No `.desktop` session file yet. No GDM integration yet. This stage only
answers: does the compositor + app combination work at all.

**Exit condition:** ARKtube runs fullscreen under GNOME Kiosk, started by
hand, and can be stopped without leaving anything broken.

---

## Stage 1 — Selectable session

**Question:** Can ARKtube be chosen from GDM's gear icon?

Steps:

* Write the session `.desktop` file GDM reads to populate the gear menu,
  pointing at a launch script rather than the raw binary.
* Write the launch script itself: start GNOME Kiosk, have it start
  ARKtube as the kiosk's managed application.
* Confirm the entry appears in the gear menu and that selecting it,
  authenticating, and landing in ARKtube works end to end from a cold
  login screen.

**Exit condition:** `Login screen → Gear → ARKtube → authenticate → ARKtube
fullscreen`, with no manual steps.

---

## Stage 2 — Session lifecycle

**Question:** Does the session behave like a real session, not just a
window that appeared?

Steps:

* Wire session lock so it uses the normal Ubuntu/GDM lock mechanism rather
  than anything custom.
* Confirm unlocking returns to the running ARKtube session rather than
  restarting it.
* Wire logout so it terminates the kiosk session and returns cleanly to
  the GDM login screen.
* Confirm ARKtube exiting on its own does not leave a dead-but-logged-in
  session, and does not silently drop the user onto a usable desktop.

**Exit condition:** Lock, unlock, and logout all behave the way they would
in a normal Ubuntu session, with ARKtube as the only thing running inside
it.

---

## Stage 3 — Input mapping

**Question:** Do the keyboard and controller inputs ARKtube actually needs
reach it, without pulling in GNOME's usual desktop shortcut set?

Steps:

* Enumerate the specific keys/buttons ARKtube's own input handling expects
  (see `main`'s controller/remote support in its root `README.md`).
* Confirm those reach ARKtube through the kiosk compositor with GNOME
  Kiosk's default keybinding hardening left in place.
* Only re-enable a specific compositor-level shortcut (e.g. VT switching)
  if a real deployment need shows up — not by default.

**Exit condition:** ARKtube's own arrow-key/D-pad/face-button navigation
works inside the session, and no unrelated GNOME Shell shortcuts leak
through.

---

## Stage 4 — Hardening

**Question:** Is this safe to leave logged into unattended?

Steps:

* Confirm developer tooling (inspector, devtools) is disabled in whatever
  ARKtube build this session launches — this is an ARKtube build
  concern, but Webtop should verify it rather than assume it.
* Confirm no path exists from inside the session back out to a general
  desktop, a terminal, or an arbitrary application launcher.
* Confirm the session survives repeated login/logout/lock cycles without
  accumulating stray processes or leftover state.

**Exit condition:** Matches the "Production expectations" checklist in the
root `README.md`.

---

## Stage 5 — Documentation and handoff

**Question:** Could someone else reproduce this setup from the docs alone?

Steps:

* Record the exact `.desktop` file, launch script, and any GNOME Kiosk
  flags used, in whatever location this branch settles on for
  configuration (not just in this document).
* Log anything that broke along the way in `docs/bugs-caught/`, one file
  per bug, per that directory's own format.
* Confirm the root `README.md`'s description of the flow still matches
  what was actually built.

**Exit condition:** A clean Ubuntu install, following only the committed
config and docs, reaches `Gear → ARKtube → ARKtube session` with no
undocumented manual steps.

---

## Non-goals of this staging

This staging does not include:

* building or modifying ARKtube itself — that's `main`,
* a general-purpose kiosk framework reusable for other applications,
* supporting distributions other than Ubuntu/GDM unless a real need
  appears later.

Each stage should be small enough that skipping ahead has no appeal.
