# The system settings overlay

**Status:** In progress -- staged implementation, this doc tracks each
stage as it lands.
**Built on:** `arktube_linux`'s existing `GtkOverlay` (`root_overlay` in
`src/main.c`), the same mechanism the boot splash and no-internet
screens already use.
**Supersedes:** the separate `overlay.py` + `wlr-layer-shell-v1` process
on the `arktube-layer-shell` branch (`src/overlay/overlay.py`,
`src/session/sway/config.d/20-arktube.conf`). That branch is not
deleted or rewritten -- it's left as the historical record of why this
approach changed; this doc is the record for `main`, where the overlay
now actually lives.

## Why this isn't a second process anymore

The `arktube-layer-shell` branch ran the overlay as its own GTK3 +
`gtk-layer-shell` process, positioned above ARKtube's own window as a
`wlr-layer-shell-v1` surface on the compositor's `overlay` layer, with
Sway `bindsym`s (`Menu`, `$mod+s`, `XF86PowerOff`) sending it POSIX
signals (`SIGUSR1`/`SIGUSR2`) to open a panel. That design chased a
real, well-documented class of Sway/wlroots bugs that are specific to
combining a `fullscreen global` client with a separate overlay-layer
client:

* `swaywm/sway#6501` -- an overlay-layer surface renders and takes
  keyboard focus above a `fullscreen global` client, but never receives
  pointer events. Filed against Sway 1.6.1, closed with no linked fix.
* `swaywm/sway#6149` -- an overlay-layer client's updates interacting
  with a fullscreen client's direct scan-out causes the fullscreen
  surface to freeze on a stale frame until the next frame it renders
  itself.
* `swaywm/wlroots#1300` and `swaywm/sway#8388` -- the protocol's own
  "overlay always wins over fullscreen" ordering has had real
  regressions across wlroots/Sway versions, so it isn't a guarantee
  independent of exactly which build is running.

None of this is specific to `overlay.py`'s own code -- `mako`, `slurp`,
and other unrelated overlay-layer clients hit the same bugs against the
same fullscreen-global combination (see the chat history that produced
this doc for the full research trail). It's an unresolved rough edge in
wlr-layer-shell-v1 itself, not something fixable from ARKtube's side of
the protocol.

Moving the panel into `arktube_linux`'s own process sidesteps the whole
bug class rather than working around it: the panel is now a plain GTK
widget stacked inside the same `GtkOverlay` as the WebView, splash
screen, and no-internet screen. There's no second Wayland surface to
race ARKtube's own `fullscreen global` for a compositor layer, so
there's nothing for those bugs to apply to. This also means it's no
longer Sway-specific -- the panel works the same way under any
compositor (or window manager, or OS) this app runs on, since it never
depended on `wlr-layer-shell-v1` in the first place.

## Staged implementation

### Stage 1 -- skeleton (done)

A single `GtkOverlay` child (`arktube_create_settings_panel()`),
full-width, anchored to the top of the window, hidden by default.
Toggled fully open/closed by the remote's Menu key or `Super+S` from an
attached keyboard, handled directly in `on_window_key_press()` -- no
Sway bindsym, no signal bridge to a second process. Deliberately no
small always-on "pill" affordance the way `overlay.py`'s collapsed
corner bar was; the panel is either fully shown or fully hidden. Proved
out placement and the toggle mechanism with no real controls behind it
yet.

### Stage 2 -- real controls (done)

Ported from `overlay.py`'s working (non-placeholder) tiles:

* **Volume** -- `wpctl get-volume`/`set-volume`, matching `overlay.py`'s
  own choice of PipeWire/WirePlumber over PulseAudio. No `pactl`
  fallback yet (see "Not yet ported" below).
* **Brightness** -- `brightnessctl get`/`max`/`set`.
* **Network** -- status only (`arktube_check_internet_now()`, the same
  raw `connect(2)` probe the boot/no-internet screen already uses, not
  a separate `nmcli`-based notion of "online" that could disagree with
  it).
* **Power** -- Restart/Shut Down/Log Out via `systemctl reboot`,
  `systemctl poweroff`, and `loginctl terminate-session`, using the same
  `$XDG_SESSION_ID`-first, never-empty-argument logic `overlay.py`'s own
  `logout()` documents and the same close-our-own-window last resort if
  no session ID can be resolved at all.

All commands run through `g_spawn_sync()`/`g_spawn_async()` (never
`system()`/`popen()`), so nothing here goes through a shell or is
vulnerable to argument injection. Status labels refresh once
immediately when the panel opens and then on a 3-second timer for as
long as it stays open; the timer is started/stopped by
`arktube_toggle_settings_panel()` so this app isn't polling
`wpctl`/`brightnessctl`/a `connect(2)` probe for its entire runtime,
only while someone is actually looking at the panel -- same reasoning
`overlay.py` had for its own status polling.

### Not yet ported

Carried over from `overlay.py`'s own `PLACEHOLDER_TILES` staging note,
plus a few things stage 2 deliberately deferred:

* **Bluetooth, Sound (device picker), Picture** -- `overlay.py` never
  had real backends for these either; still open.
* **A real Network tile** -- Wi-Fi network list / connect (`nmcli
  device wifi list` + `nmcli device wifi connect`), not just an
  online/offline status label.
* **PulseAudio (`pactl`) fallback** for volume, for a system running
  PulseAudio instead of PipeWire.
* **A confirmation step before Shut Down/Restart.** `overlay.py`'s
  dedicated centered `'power'` panel state existed partly to make
  powering off a deliberate second action (open the panel, then tap
  Power) rather than reachable in one tap from the main panel. Stage 2's
  Power tile is one click, with no confirmation dialog -- a real gap
  worth closing before this ships anywhere a stray remote press matters.
* **Lock screen.** `overlay.py`'s `lock()`/`unlock()`
  (`keyboard_mode=EXCLUSIVE` while locked) has no equivalent here yet.
* **The `systemd-inhibit --what=handle-power-key:idle` wrapper**
  `20-arktube.conf` execs `overlay.py` through, so the physical Power
  key reaches this app's own handling instead of logind's default
  instant `systemctl poweroff`, and so idle/suspend is inhibited for the
  session's lifetime. `main` has no session/compositor config layer at
  all yet (that's specific to the `arktube-layer-shell` branch), so
  there's currently nothing on `main` to carry this even once Power-key
  handling exists here.
* **D-Pad navigation between tiles/buttons** for remotes with no
  pointer -- stage 2's buttons are plain `GtkButton`s relying on
  whatever tab/arrow-key focus traversal GTK gives them for free; not
  verified against real remote input.

## What was not verified

Same caveat every doc in this tree carries for anything that needs a
real display/input session: this was built and compiled
(`cmake --build .`, clean under `-Wall -Wextra`) in an environment with
no display server, no PipeWire/WirePlumber, no `brightnessctl`-capable
backlight, and no real session to log out of or restart. The GTK
widget tree, CSS, and command-spawning logic are real and compile
clean, but on-hardware behavior -- whether the panel actually renders
where intended, whether `wpctl`/`brightnessctl` are present and behave
as expected on the target image, whether D-Pad-only input can reach and
activate the buttons at all -- has not been exercised end-to-end here.
