# XDG_CURRENT_DESKTOP documented as imported, wasn't

**Found:** Second full sweeping scan, diffing `SYSTEM_DESIGN.md`'s own
quoted config sample against the actual shipped
`10-systemd.conf`, rather than assuming a doc and its subject stayed
in sync.
**Environment:** Not hardware-specific — a documentation/implementation
drift, reproducible by reading the two files side by side.

## Symptom

None observed directly, same caveat as every entry in this log.
`docs/foundational/SYSTEM_DESIGN.md`'s "Session integration" section
quotes:

```
exec systemctl --user import-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP
exec systemctl --user start sway-session.target
```

as this layer's own config. The actual
`src/session/sway/config.d/10-systemd.conf` imports
`{,WAYLAND_}DISPLAY SWAYSOCK` — i.e. `DISPLAY`, `WAYLAND_DISPLAY`, and
`SWAYSOCK` — and never imported `XDG_CURRENT_DESKTOP` at all. The two
had drifted apart at some point after the doc was written; nothing
flagged it because nothing checks a doc's inline code sample against
the file it's describing.

## Root cause

Documentation drift, not a design decision — no comment anywhere in
`10-systemd.conf` explains `XDG_CURRENT_DESKTOP` being absent, and
the file's own header attributes this fragment to "the sway wiki's
own Systemd-integration page," which does not omit it either. Sway's
own process environment does have `XDG_CURRENT_DESKTOP` (populated
from `arktube.desktop`'s `DesktopNames=sway` via GDM/pam_systemd at
session start) — but that's a different environment than the
`systemctl --user` manager's own environment block, which only gets a
variable through an explicit `import-environment` call like this one
or an `environment.d` file. Anything started later under
`sway-session.target` that reads `$XDG_CURRENT_DESKTOP` from its own
systemd-provided environment (the common case being
`xdg-desktop-portal` backend selection) would see it unset, not
"sway."

## Fix

`src/session/sway/config.d/10-systemd.conf`: added
`XDG_CURRENT_DESKTOP` to the `import-environment` call, matching
`SYSTEM_DESIGN.md`'s own sample.

## Verification

Not verified against a real display. What was checked: the two files
read side by side, confirming the mismatch was real and not a
misreading; `systemctl --user import-environment`'s own accepted
syntax (a space-separated list of variable names, brace expansion
handled by the shell before `systemctl` ever sees it) was not changed
in a way that would break the existing `{,WAYLAND_}DISPLAY`
expansion.

## Prevention

An inline code sample in a design doc is itself a claim about the
implementation, and can drift the same way any other claim can. Diff
it against the real file occasionally, the same way this scan did,
rather than treating doc prose as automatically current.
