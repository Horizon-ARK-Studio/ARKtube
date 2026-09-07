# Bugs caught

A running log of bugs found in this branch's compositor layer, and
what actually fixed them.

This directory previously had no individual entries. Two are logged
below, both found by a full sweeping scan of this branch's compositor
layer for the class of bug where behavior is silently controlled by
something other than what a reader would assume — a different systemd
mechanism, a different layer-shell layer, a doc describing intent
rather than implementation.

## When something belongs here

Log a bug here if it was specific to the compositor layer — Sway
configuration, `wlr-layer-shell-v1` placement, idle-inhibit, seat/
input handling, or session-readiness signaling. If the bug was in
ARKtube's own application code, it belongs in `main`'s
`docs/bugs-caught/` instead. If it was in session lifecycle, lock,
logout, or GDM integration, it belongs in `webtop`'s
`docs/bugs-caught/`. When in doubt, log it where the fix actually
landed, not where the symptom was first seen.

## Template

Copy this into a new file named for the bug (`kebab-case.md`), fill
in every section, and add a row to the index below.

```markdown
# <Short, specific title>

**Found:** <date or stage>
**Environment:** <Sway version, wlroots version, distro/kernel if relevant>

## Symptom

What was actually observed — the exact behavior, not the assumed
cause. Include error output verbatim where there is any.

## Root cause

What was actually wrong, confirmed rather than guessed. If the
investigation took a wrong turn first, say so — a false lead that
looked plausible is useful information for the next person who hits
something similar.

## Fix

What changed, and where (file/config/commit). Enough detail that
someone could apply the same fix without re-deriving it.

## Verification

How the fix was confirmed to actually work — not just "seems fine
now." What was tested, and under what conditions.

## Prevention (optional)

If this class of bug could recur elsewhere, what would catch it
earlier next time.
```

## Index

| Bug | Environment | One-line summary |
|---|---|---|
| [`idle-inhibit-gap.md`](idle-inhibit-gap.md) | Not hardware-specific | SYSTEM_DESIGN.md's idle-inhibit success criterion was unimplemented, and even implemented would have missed logind's separate `IdleAction`; fixed by extending the existing `systemd-inhibit` wrapper to `--what=handle-power-key:idle`. |
| [`swaybar-top-layer-fullscreen.md`](swaybar-top-layer-fullscreen.md) | wlr-layer-shell-v1, protocol-level | Closed an open item defensively with `bar { mode invisible }`, after research suggested (but couldn't confirm on real hardware) that fullscreen-vs-top-layer stacking already prevented the feared overlap. |

## Non-goals

* This is not a general Sway or wlroots troubleshooting FAQ — only
  bugs actually caught in this branch's own work belong here.
* This is not a place to log a bug that was never actually fixed. An
  open, unresolved issue belongs in the project's issue tracker; this
  directory is a record of root cause and fix, not a TODO list.
