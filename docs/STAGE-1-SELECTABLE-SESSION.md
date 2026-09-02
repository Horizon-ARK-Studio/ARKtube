# Stage 1 — Selectable session

**Status:** Implemented, not yet run end to end against a real GDM login
screen (this was built in a headless container with no display manager).
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 1.

## What was verified, and how

Rather than build against a description of how `gnome-kiosk-script-session`
works, its actual `.deb` (`46.0-1build2`, the version Ubuntu 24.04 Noble
ships) was downloaded with `apt-get download` and extracted with
`dpkg-deb -x`, and every file it installs was read directly:

| Path it installs | Role |
|---|---|
| `usr/share/wayland-sessions/gnome-kiosk-script-wayland.desktop` | GDM gear-menu entry (Wayland) |
| `usr/share/xsessions/gnome-kiosk-script-xorg.desktop` | GDM gear-menu entry (X11) |
| `usr/share/gnome-session/sessions/gnome-kiosk-script.session` | `RequiredComponents=org.gnome.Kiosk;org.gnome.Kiosk.Script;` |
| `usr/lib/systemd/user/org.gnome.Kiosk.Script.service` | Runs `/usr/bin/gnome-kiosk-script`, `Restart=always` |
| `usr/bin/gnome-kiosk-script` | A shell stub — see below |

`usr/bin/gnome-kiosk-script`'s actual content:

```sh
if [ ! -e ~/.local/bin/gnome-kiosk-script ]; then
    # ...writes a placeholder example script there, opens a text editor...
fi
exec ~/.local/bin/gnome-kiosk-script "$@"
```

So the package's own logic confirms the scope: `~/.local/bin/gnome-kiosk-script`
is the one file it leaves for a deployer to supply. That part of the
original plan was right. Two parts of it weren't:

## Correction 1 — the gear menu does not say "ARKtube" by default

The stock `.desktop` files' `Name=` fields are literally
`Kiosk Script Session (Wayland Display Server)` /
`(X11 Display Server)`. `STAGED-IMPLEMENTATION.md`'s Stage 1 steps say to
"write the session `.desktop` file GDM reads to populate the gear menu" —
that line is correct and necessary, not redundant with the package. Without
it, Stage 1's exit condition (`Gear → ARKtube → authenticate → ARKtube
fullscreen`) isn't met on naming grounds: there is no menu item called
ARKtube.

Fix: `session/wayland-sessions/arktube.desktop` and
`session/xsessions/arktube.desktop` are new files, not edits to the
package's own files (editing a package-owned file under `/usr/share` would
show up as a modified conffile on the next `apt` update). They set
`Name=ARKtube` and reuse `Exec=gnome-session --session gnome-kiosk-script` —
the same session ID the stock files launch — so the existing
`.session` file, systemd unit, and `RequiredComponents` wiring are reused
as-is. Net effect: the gear menu will show both "ARKtube" and "Kiosk
Script Session" entries, launching the identical session; only the former
is meant to be used.

## Correction 2 — the ARKtube binary path wasn't a guess

`main`'s `packaging/linux/build-deb.sh` was read directly rather than
guessing an `ARKTUBE_BIN` path. A `.deb` install of ARKtube puts a launcher
at `/usr/bin/arktube` (wrapping `/usr/lib/arktube/ARKtube`, with its own
`NL_PATH`/stale-Chrome handling already built in — see that script's
comments). So `session/gnome-kiosk-script` is just:

```sh
exec arktube
```

No embedded path, no `--path=` argument duplicated here — `arktube` already
does that.

## A real Stage 2 landmine, confirmed here rather than assumed

`org.gnome.Kiosk.Script.service` (read directly from the extracted `.deb`)
has `Restart=always` with no `RestartSec` override. The root `README.md`
already flags that GNOME Kiosk 50 changed script-session behavior so a
script exiting can end the session; Noble's `gnome-kiosk` package is
`46.0-1build2`. Combined, this means: on the OS most people will actually
run this on, ARKtube exiting on its own — a crash, or a deliberate quit —
does not return to the login screen. `systemd --user` immediately restarts
`gnome-kiosk-script`, which re-execs `arktube`. Not a dead session, not a
silent drop to a usable desktop either — just an unremovable relaunch loop
until the compositor itself is killed. This doesn't change Stage 1's scope
(the README already anticipated it, and it's listed as Stage 2 work in
`STAGED-IMPLEMENTATION.md`), but it's now confirmed from the actual unit
file rather than inferred from changelog text, so Stage 2 can go straight
to addressing it instead of re-deriving it.

## What's genuinely unverified

* No real GDM login screen was clicked through. This container has no
  display manager, so "the gear menu shows ARKtube and selecting it reaches
  a fullscreen ARKtube session" is built to spec, not observed. That's
  Stage 1's actual exit condition and it should be confirmed on real
  hardware/VM before calling this stage done.
* `install-webtop-session.sh` assumes ARKtube's `.deb` (built via `main`'s
  `build-deb.sh`) is already installed; it doesn't build or install it.

## Files

* `session/gnome-kiosk-script` — deployed to `~/.local/bin/gnome-kiosk-script`
* `session/wayland-sessions/arktube.desktop`, `session/xsessions/arktube.desktop` — new gear-menu entries
* `session/install-webtop-session.sh` — installs `gnome-kiosk` + `gnome-kiosk-script-session`, adds the two `.desktop` files, deploys the script
