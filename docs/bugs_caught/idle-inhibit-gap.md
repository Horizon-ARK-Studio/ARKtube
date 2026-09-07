# Idle-inhibit was documented, not implemented

**Found:** Full sweeping scan of the compositor layer, after the
initial Sway cutover and REMOTE-INPUT-MAPPING work landed.
**Environment:** Not hardware-specific -- a documentation/implementation
gap, reproducible by reading the source rather than by running it.

## Symptom

None observed directly (no display in this environment -- same
caveat every prior stage carries). Found by checking
`docs/foundational/SYSTEM_DESIGN.md`'s own stated success criterion --
"Idle-inhibit holds the session awake during active playback and
releases it otherwise" -- against the actual code, rather than
assuming the doc and the implementation agreed.

## Root cause

Two separate gaps stacked on top of each other:

1. Nothing in this branch ever requests an idle inhibitor of any
   kind. Not `overlay.py` (grepped: no `idle` request beyond
   unrelated `GLib.idle_add` main-loop calls), not `main`
   (`arktube_linux/src/main.c`, grepped clean too). Sway *implements*
   `wlr-idle-inhibit-manager-v1` as a compositor feature, the same way
   it implements `wlr-layer-shell-v1` -- but implementing a protocol
   is not the same as anything using it. SYSTEM_DESIGN.md's wording
   ("The overlay or ARKtube *can* hold the session awake...") reads,
   on a second pass, as describing a capability, not a confirmed
   behavior -- but its own "Success criteria" section states the
   holding/releasing as a fact about this layer, which it isn't yet.

2. Even a correct `wlr-idle-inhibit-manager-v1` request would only be
   half the fix. That protocol inhibits a *compositor's own* idle
   handling -- which nothing in this branch configures anyway; there
   is no `swayidle` invocation anywhere in `src/session/`. Whether the
   machine actually suspends on inactivity is controlled by a
   completely separate mechanism: systemd-logind's own
   `IdleAction`/`IdleActionSec` (see `logind.conf(5)`), which tracks
   idle state independently and is not touched by the Wayland
   idle-inhibit protocol at all. This is a well-documented point of
   confusion elsewhere -- see `swaywm/swayidle#4` and
   `systemd.io/INHIBITOR_LOCKS` -- not something specific to this
   project, but this branch had not checked its own design against it
   before this scan.

## Fix

`src/session/sway/config.d/20-arktube.conf`: the `systemd-inhibit`
wrapper already execs `overlay.py` for `handle-power-key` (see that
file's own extensive comment on why). Extended `--what=` to
`handle-power-key:idle`, which additionally takes logind's own `idle`
inhibitor for the whole time `overlay.py` runs -- i.e. the whole
session. This is the same mechanism, same lock lifetime, same
self-cleaning-on-exit property the power-key fix already established;
no new dependency, no new process.

This is deliberately session-wide, not playback-scoped, since
ARKtube currently has no way to signal "playback is active" out to
Sway or the overlay. See the comment above the `exec` line for the
reasoning and what would need to exist to narrow it later.

## Verification

Not verified against a real display -- no display manager in this
environment, same caveat as everything else in this project. What
*was* checked: `systemd-inhibit --what=handle-power-key:idle ...`
against `systemd-inhibit(1)`'s own documented `--what=` grammar
(colon-separated list of `shutdown`, `sleep`, `idle`,
`handle-power-key`, `handle-suspend-key`, `handle-hibernate-key`,
`handle-lid-switch`) -- `idle` is a valid member of that list
alongside `handle-power-key`, confirmed against the man page rather
than assumed. Confirming the lock is actually held during a running
session (`systemd-inhibit --list` or the equivalent `gdbus
introspect`) is still open against real hardware.

## Prevention

`SYSTEM_DESIGN.md`'s "Success criteria" section states behavior as
fact. Any future addition to that list should be checked against the
actual code the same way this one was, not assumed correct because it
reads as a reasonable design.
