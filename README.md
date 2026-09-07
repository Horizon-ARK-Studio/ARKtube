# ARKtube Webtop — Sway Edition

## One app. One screen. Nothing else.

Webtop is the layer that turns a login into ARKtube.

This edition builds that layer on **Sway** — a Wayland compositor,
chosen for one reason: it does almost nothing on its own, and that's
exactly what a kiosk needs.

---

## Why Sway

Sway is a tiling Wayland compositor built on `wlroots`. It has no
shell, no panel, no dock, and no desktop metaphor to switch off.

For a normal desktop, that's a limitation.

For ARKtube, it's the point.

```text
GNOME Shell   → a desktop, with a kiosk mode bolted on
GNOME Kiosk   → a stripped desktop compositor
Sway          → a compositor, with nothing to strip
```

There's less to disable, because there was less there to begin with.

---

## What this edition does

* replaces the session compositor with **Sway**
* launches ARKtube as the only window Sway ever shows
* forces that window fullscreen, borderless, and gapless — on every
  output
* disables the workspace, tiling, and window-switching behavior
  ARKtube never needs
* keeps lock, logout, and power handled by the session — not faked
  in JavaScript
* returns cleanly to the login screen on exit, every time

Everything else about Webtop's contract stays the same. Only the
compositor underneath it changes.

---

## The session, end to end

```text
Login screen
     │
     ▼
   Sway starts, headless of any UI
     │
     ▼
   ARKtube launches as the sole window
     │
     ▼
   fullscreen global · no border · no gaps
     │
     ├── Lock   → session lock, ARKtube stays put
     ├── Logout → Sway exits, ARKtube exits with it
     └── Power  → handled by the session, not the app
     │
     ▼
Login screen
```

No panel ever draws. No workspace ever gets a chance to switch.

---

## Configuration

Sway's entire kiosk posture lives in one config file.

```text
# /etc/sway/config.d/arktube.conf

# ARKtube is the only thing this session runs
exec arktube

# it owns the whole output, always
for_window [app_id="arktube"] fullscreen global

# no chrome, no seams
default_border none
gaps inner 0
gaps outer 0

# nothing to tile, nothing to switch
bindsym $mod+Shift+q kill
```

Everything not needed for ARKtube is left out — not disabled, just
never written.

---

## Session integration

Sway doesn't ship a systemd unit of its own, so the session defines
one:

```text
~/.config/systemd/user/sway-session.target
~/.config/systemd/user/sway-session.service
```

Sway signals its own readiness at the end of its config:

```text
exec systemctl --user import-environment WAYLAND_DISPLAY XDG_CURRENT_DESKTOP
exec systemctl --user start sway-session.target
```

That target is what the rest of the session — locking, power,
network — binds to. When it stops, the session is over.

---

## What this is not

* not a general-purpose Sway setup
* not a tiling window manager, in practice — there is only ever one
  window
* not a place for a bar, a launcher, or a status line
* not a replacement for Ubuntu, GDM, or the login screen

Sway is infrastructure here. It is not meant to be seen.

---

## Requirements

* `sway`, built with `wlroots`
* a `seatd`-managed seat, or `elogind`/`systemd-logind`
* Sway's optional companions — `swaylock`, `swayidle` — for lock and
  idle behavior, if the session doesn't handle these itself

Nothing else. No `waybar`, no `wofi`, no terminal, no launcher.

---

## Status

🚧 **Compositor swap, in progress.**

* [x] Sway launches ARKtube as the sole window
* [x] Fullscreen, borderless, gapless across all outputs
* [x] Session target wired to lock / logout / power
* [x] `seatd` vs. `logind` decided — `logind`, since Ubuntu ships it and
      Sway uses it as its seat backend automatically; no `seatd` needed
* [x] Selectable from GDM's gear icon — see `install.sh` and
      `src/session/`
* [ ] Idle and lock behavior finalized against `docs/STAGE-8`
* [ ] Confirm the default swaybar doesn't show through over ARKtube's
      fullscreen window — see the note in
      `src/session/sway/config.d/20-arktube.conf`

---

## Philosophy

Sway doesn't add a desktop.

It adds a window, and gets out of the way.

```text
              ┌──────────────┐
              │   ARKtube    │
              └──────┬───────┘
                     │
               the only window
                     │
              ┌──────▼───────┐
              │     Sway     │
              └──────┬───────┘
                     │
               nothing else
```

If a feature isn't required to show ARKtube, full-screen, on one
screen — it doesn't belong in this config.

That's the whole edition.
