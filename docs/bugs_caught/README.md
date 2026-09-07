# Bugs caught

A running log of bugs found in this branch's compositor layer, and
what actually fixed them.

This directory is currently empty of individual entries. That's
accurate, not an oversight — log a bug here the first time this
branch actually catches one, using the template below, rather than
backfilling history that wasn't written down at the time.

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
| _none logged yet_ | | |

## Non-goals

* This is not a general Sway or wlroots troubleshooting FAQ — only
  bugs actually caught in this branch's own work belong here.
* This is not a place to log a bug that was never actually fixed. An
  open, unresolved issue belongs in the project's issue tracker; this
  directory is a record of root cause and fix, not a TODO list.
