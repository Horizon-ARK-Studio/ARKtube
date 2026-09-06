# ARKtube-under-Cage session

This directory is not part of Cage itself -- it's the ARKtube session
wiring, carried over from the `webtop` branch's own `session/` and
adapted for this branch's compositor rather than GNOME Kiosk. It exists
here, rather than only on `webtop`, because `webtop`'s system overlay
(`session/overlay/overlay.py`, vendored below) already assumed the
wlr-layer-shell-v1 support this branch adds -- see that file's module
docstring -- so a build of *this* branch was never actually testable as
a full session on its own until this was added.

## Layout

- `overlay/` -- the TV-style system overlay (volume, brightness,
  network, lock, logout, power), copied unmodified from `webtop`'s
  `session/overlay/`. It has no GNOME-Kiosk-specific code in it (it
  only ever shells out to NetworkManager/PipeWire/upower/brightnessctl/
  loginctl -- see its own module docstring), so nothing needed to
  change here.
- `cage/arktube-cage-session` -- the session's actual entry point,
  adapted from `webtop`'s `session/gnome-kiosk-script`. Forks the
  overlay, then `exec cage -d -- arktube_linux`. See its own comments
  for what's different about lifecycle/cleanup versus the GNOME Kiosk
  version.
- `wayland-sessions/arktube-cage.desktop` -- the GDM gear-menu entry,
  pointing straight at `arktube-cage-session` rather than at
  `gnome-session --session gnome-kiosk-script`. No X11/xsessions
  equivalent -- Cage is Wayland-only (see `cage.1.scd`).
- `install-arktube-cage-session.sh` -- builds this branch's Cage,
  installs it, and deploys everything above. Does not touch or remove
  `webtop`'s existing GNOME Kiosk session; both are left installed side
  by side to compare, per `docs/foundational/CAGE-MIGRATION.md`'s own
  "don't cut over until proven" staging (that doc lives on `webtop`).

## What's verified vs. what isn't

Confirmed by reading the code on both sides (see the parity check this
was written from): the wlr-layer-shell-v1 support this branch adds
covers what `overlay.py` actually calls -- `OVERLAY`-layer surfaces,
`TOP`/`BOTTOM`/`LEFT`/`RIGHT` anchoring, `ON_DEMAND` and `EXCLUSIVE`
keyboard interactivity (`layer_shell.c`'s `handle_layer_surface_map()`
and `layer_shell_handle_pointer_press()`) -- so `lock()`/`unlock()`'s
full-output `EXCLUSIVE` surface and the normal top-right `ON_DEMAND`
strip should both actually work, not just compile.

Not yet run end-to-end on real GDM+logind hardware: whether a logout
actually reaps the overlay process cleanly (see
`arktube-cage-session`'s own comment on this), and Stages 11-13 from
`CAGE-MIGRATION.md` (lifecycle, input mapping, hardening) generally.
Treat this as "ready to start testing," per the ask that produced it,
not as those stages already having passed.
