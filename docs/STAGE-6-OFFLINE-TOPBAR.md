# Stage 6 — Offline system topbar

**Status:** Implemented at the code level. Not yet click-tested against a
real GDM session, for the same reason every prior stage carries that
caveat — no display manager in this environment.
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 6.
**Built on:** Stage 1's session entry point, Stage 4's `KillMode=control-
group` hardening (this stage's process lifecycle rides directly on that,
rather than adding a new one).

## Why this stage exists

Stages 1–4 confirmed something true and, on its own, correct: GNOME
Kiosk ships no panel, dock, or status bar by design
(`docs/foundational/PROBLEM-STATEMENT.md`). That's exactly what makes it
suitable for a single-application session.

It also means the root README's own promises — "provide the required
session controls," "allow the session to be locked," "allow the user to
log out" — had, until this stage, nothing inside the session actually
providing them. Locking and logout were reachable in the abstract
(`loginctl`, `systemctl` exist on the machine) but not from anywhere a
user sitting in front of ARKtube could reach without a keyboard shortcut
or a terminal — both of which Stage 4 confirmed are exactly what this
kiosk session is supposed to not expose.

The "no network" requirement is the actual design constraint, not a
throwaway line: a kiosk box that can only be locked, logged out, or
have its volume changed while it happens to have working internet isn't
really providing session controls, it's providing them *sometimes*.

## What was built

`session/topbar/` — a small pywebview application:

| File | Role |
|---|---|
| `topbar.py` | Backend. Owns the window and every system call. |
| `static/index.html` | Bar + two popover panels (calendar/notifications, quick settings). |
| `static/style.css` | Dark theme matching the target session's own look. |
| `static/app.js` | Panel open/close, status polling, calendar rendering — talks only to `pywebview.api.*`. |
| `requirements.txt` | `pywebview`; system-level GTK/WebKit deps are apt packages, listed in the installer instead (pip can't provide those). |

### Window model

The window is a single frameless, always-on-top, fixed-width rectangle
pinned at `(0, 0)`. Collapsed, it's a 32px strip spanning the screen
width — the bar itself. Opening a panel calls back into Python
(`set_panel`), which resizes the same window taller (460px for quick
settings, 720px for the calendar/notifications panel); the extra height
is where the panel's HTML actually renders, anchored to the top-right or
top-left depending on which panel.

This was a deliberate choice over a full-screen transparent overlay with
per-pixel click-through: pywebview's GTK backend doesn't expose the
input-shape masking (X11 `XShape`) or Wayland input-region APIs that
would be needed to let clicks pass through the transparent areas of a
full-screen window down to ARKtube underneath. Rather than build that —
a compositor-level integration outside pywebview's own surface, and a
real unknown for effort — the window only ever occupies the space it's
actually using. This matches how GNOME Shell's own quick-settings and
calendar popovers already behave in practice: opening either dims/blocks
interaction with what's behind it until you click away, it doesn't let
clicks reach through to the desktop underneath the popover either.

### Every control is local, on purpose

| Control | Backed by | Falls back to |
|---|---|---|
| Clock | JS `Date()` | — (never touches Python) |
| Battery | `upower -e` / `upower -i` | Hidden if no battery device |
| Volume | `wpctl get-volume` / `set-volume` | `pactl` if `wpctl` is missing |
| Brightness | `brightnessctl` | Slider disabled if unavailable |
| Wi-Fi status/toggle | `nmcli` | Tile disabled, marked "Unavailable" |
| Airplane Mode | `nmcli radio wifi`/`wwan` | Same as above |
| Night Light / Dark Style | `gsettings` (`org.gnome.settings-daemon.plugins.color`, `org.gnome.desktop.interface`) | Toggle silently no-ops if the schema isn't installed |
| Lock | `loginctl lock-session` | — |
| Logout | `loginctl terminate-session` | `loginctl terminate-user` if session id can't be read |
| Power off / Restart | `systemctl poweroff` / `reboot` | — |
| Screenshot | `gnome-screenshot` | `grim` |

Every one of these is a local process call or a D-Bus-backed CLI tool
talking to logind/systemd/NetworkManager on the same machine. None of
them constructs a URL, opens a socket to anything off-host, or has any
code path that behaves differently with Wi-Fi disabled. `run()` in
`topbar.py` treats a missing tool as "control unavailable," not an
error — the UI degrades (a disabled slider, a tile marked
"Unavailable") rather than crashing the whole topbar because one CLI
tool isn't installed on a given machine.

### Lifecycle: piggybacking on Stage 4, not inventing a new one

The topbar is launched from `session/gnome-kiosk-script` itself,
backgrounded (`&`) immediately before `exec arktube`. That specific
placement matters: a process forked before an `exec` stays in the
parent's process group and — separately, and more importantly here —
its cgroup, even though `exec` replaces the process image of the
original shell. Since `org.gnome.Kiosk.Script.service` is the systemd
unit running that script, and Stage 4 already pinned
`KillMode=control-group` on it, the topbar gets reaped on logout for
free, the same way Stage 4 confirmed a detached Chrome child already is.
No second systemd unit, no XDG autostart entry with its own uncertain
interaction with a kiosk-mode `gnome-session` (untested territory this
stage deliberately avoided rather than guessed at) — one launch point,
one cgroup, one cleanup path.

The script checks `-x` on the installed topbar path before launching it
and does nothing if it's absent, rather than failing the whole session —
consistent with the existing script's own philosophy of not adding
respawn/failure logic it doesn't need (see that script's Stage 2
comment on why no respawn logic lives there either).

### Installer changes

`session/install-webtop-session.sh` now also:

* installs `python3-gi`, `gir1.2-webkit2-4.1` (pywebview's GTK/WebKit
  runtime — not available via pip), plus `network-manager`,
  `wireplumber`, `pulseaudio-utils`, `brightnessctl`, and `upower` so
  the common case works without each control silently degrading on a
  stock Noble install;
* `pip install`s `pywebview` itself;
* copies `topbar.py` and `static/` into
  `~/.local/share/arktube-topbar/`, the path `gnome-kiosk-script`
  checks for.

## What was verified, and how

Every backend method was read against the actual CLI tools' documented
output formats (`wpctl get-volume`'s `Volume: 0.45` shape,
`nmcli -t -f ACTIVE,SSID dev wifi`'s colon-delimited fields,
`upower -i`'s `percentage:`/`state:` lines) rather than guessed. The
calendar grid is computed from plain `Date` arithmetic with no external
library, checked by hand against the current month.

What was **not** verified, and is stated plainly rather than assumed:

* **On-hardware behavior** — the window resize approach, the popover
  positioning, whether `wpctl`/`nmcli`/`brightnessctl` behave exactly as
  documented on the actual target hardware, and whether the
  `gnome-kiosk-script` launch-order trick actually lands the topbar in
  the expected cgroup in practice. This container has no display
  manager, no PipeWire/WirePlumber session, no NetworkManager, and no
  real battery — none of Stage 6's own controls could be exercised
  end-to-end here, only read and reasoned about, the same limitation
  every prior stage has carried.
* **Do Not Disturb** — the toggle exists in the UI (matching the
  reference design) but isn't wired to anything yet; there's no
  notification daemon in this session for it to suppress against. Left
  as a visible no-op rather than either faking a working toggle or
  leaving it out of the UI entirely, since the layout was modeled
  directly on GNOME Shell's own panel.
* **Bluetooth** — the tile is present in the UI for layout parity with
  the reference design but isn't backed by `bluetoothctl` or any other
  tool yet. Genuinely out of scope for "very important: works with no
  network," since Bluetooth isn't network connectivity in the sense this
  stage's requirement is about — deferred rather than rushed.

## Exit condition assessment

The stage's exit condition — reaching accurate status and working
lock/logout/power/volume/brightness/Wi-Fi controls with the machine
offline — is met at the code level: nothing in the control path requires
a network round-trip, confirmed by reading every method in `topbar.py`
rather than by having actually pulled a cable. The on-hardware half of
that claim is the same open item every stage above has flagged, not
something this stage can close from inside a container.

## Files

* `session/topbar/topbar.py`, `session/topbar/static/*`,
  `session/topbar/requirements.txt` — new.
* `session/gnome-kiosk-script` — launches the topbar, backgrounded,
  before `exec arktube`.
* `session/install-webtop-session.sh` — installs the topbar's system
  and Python dependencies and deploys its files.
* `docs/STAGE-6-OFFLINE-TOPBAR.md` — this file.
* `docs/README.md` — added a row for this stage.
* `docs/foundational/STAGED-IMPLEMENTATION.md` — added Stage 6's
  definition, which didn't exist in the original plan.
