# Stage 8 — TV-style system overlay

**Status:** Implemented at the code level for the essentials named
below. Not yet click-tested against a real GDM session, for the same
reason every prior stage carries that caveat — no display manager in
this environment.
**Stage definition:** appended to `docs/foundational/STAGED-
IMPLEMENTATION.md` as Stage 8.
**Built on:** Stage 6/7's topbar work (`session/topbar/`, now removed —
this doc is its replacement), Stage 4's `KillMode=control-group`
hardening (this stage's process lifecycle rides directly on that, same
as Stage 6 did).
**See also:** `docs/foundational/CAGE-MIGRATION.md`, which explains why
this redesign happened at the same time the compositor underneath it
is being reconsidered — same root cause, two separate fixes.

## Why this stage exists

Stage 6 built a real thing — a working, offline-first way to reach
volume, brightness, Wi-Fi, lock, logout, and power from inside the
ARKtube session — and Stage 7 made it behave correctly around
Immersive Mode. Neither of those were wrong.

What was wrong was the reference point. Stage 6 modeled the topbar
directly on GNOME Shell's own top bar and quick-settings popover: a
thin strip across the *entire* width of the screen, small pill-shaped
buttons, a calendar, a notification list, panel-style icon buttons
sized for a mouse pointer and a monitor at arm's length.

ARKtube is not a desktop application. It's a TV appliance, meant to be
picked up on a couch and navigated with a remote or a game controller.
Desktop panel chrome — small hit targets, a full-width strip, GNOME's
own icon language — doesn't fit that, independent of whether the
controls behind it were correct. Carrying it over was the same mistake
covered in `docs/foundational/CAGE-MIGRATION.md`: reaching for the
GNOME-shaped default instead of the shape this product actually needs.

## What changed

`session/topbar/` is removed. `session/overlay/` replaces it:

* **Collapsed state:** a small corner affordance (`#launcher` in
  `session/overlay/static/index.html`) — not a full-width bar. It shows
  the current network icon (Wi-Fi or Ethernet, whichever is active),
  the volume icon, and a battery percentage pill *only when a battery
  is present* (see "Battery / laptop detection" below).
* **Expanded state:** a single panel, anchored to the top-right corner
  rather than spanning the screen, with:
  * a row of four large, remote-navigable tiles — **Network**,
    **Picture**, **Sound**, **Bluetooth** — modeled on the *style and
    intent* of Google/Android TV's own settings overlay (big tiles,
    generous focus rings, one tile's content replacing another's below
    the row) without copying it screenshot-for-screenshot;
  * a content pane below the tile row that swaps based on which tile
    is selected;
  * an always-visible slider dock at the bottom of the panel —
    brightness and volume — present regardless of which tile is
    active, because these are the two controls a viewer reaches for
    most often and shouldn't be buried behind tile navigation;
  * a small corner power icon (lock / log out / restart / power off),
    kept off the four-tile row since the product spec for this stage
    only names Network/Picture/Sound/Bluetooth as tiles, but kept
    reachable somewhere in the overlay because the root README's own
    "session controls" commitment (lock, log out) predates this
    redesign and this stage doesn't get to quietly drop it.

## Staged scope: essentials now, the rest later

This stage does **not** try to build all four tiles' real
functionality at once. Only what's essential ships now:

| Tile / control | This stage | Later stage |
|---|---|---|
| **Network** (Wi-Fi / Ethernet) | Real: live status, Ethernet-priority logic, Wi-Fi scan + connect | Saved-network management, forget-network, captive portal handling |
| **Brightness slider** | Real: `brightnessctl` | — |
| **Volume slider** | Real: `wpctl`/`pactl`, mute toggle | Per-app volume, output device switching |
| **Picture** | Placeholder ("hasn't been built yet") | Actual display settings — no target property (HDR, color, sharpness) chosen yet |
| **Sound** | Placeholder | Output device selection, equalizer, distinct from the always-on volume slider above |
| **Bluetooth** | Placeholder | Pairing, device list, `bluetoothctl` backend |

The placeholder tiles are not disabled or hidden — they're fully
reachable by remote/keyboard navigation, same as Network, and render a
shared placeholder pane (`#content-placeholder` in `index.html`,
`PLACEHOLDER_TILES` in `overlay.py`) rather than an error or a dead
end. The point of showing them now, un-implemented, is so the tile
layout doesn't need to be renegotiated later when each one gets a real
backend — only the placeholder pane gets swapped for a real one, one
tile at a time.

## Network logic: Ethernet-priority, Wi-Fi fallback

The product requirement this stage implements exactly:

> Wi-Fi settings become Ethernet settings when the machine is using
> Ethernet instead of Wi-Fi. When both are connected, Ethernet is used.
> When the connection is unstable or there's no network, it switches
> over to Wi-Fi.

`SystemAPI._network()` in `session/overlay/overlay.py` implements this
as:

1. Enumerate devices via `nmcli -t -f DEVICE,TYPE,STATE device status`.
2. If an Ethernet device is `connected` **and** `nmcli networking
   connectivity` reports `full` — use Ethernet. This is the "both
   connected" case; Ethernet wins.
3. If an Ethernet device is `connected` but connectivity is *not*
   `full` (link up, not actually passing traffic) — treat it as
   unstable. If Wi-Fi is separately connected, report Wi-Fi instead;
   otherwise report Ethernet as unstable so the overlay can say so
   rather than claiming a working connection that isn't one.
4. If there's no Ethernet at all, fall through to Wi-Fi's own connected
   state.
5. Otherwise, report "Not Connected" — no invented state.

Same "cached read, not a fresh probe" discipline Stage 6 already
established for `_internet_available()`: this is polled on a timer
(`STATUS_POLL_SECONDS` client-side), so it reads NetworkManager's
already-cached connectivity state rather than forcing a new check that
could block.

## Battery / laptop detection

`is_laptop` is derived, not configured: `SystemAPI._battery()`
enumerates `upower -e` for a battery device the same way Stage 6's
topbar did, and returns `None` when there isn't one. The frontend
(`app.js`'s `applyBattery()`) only shows the battery pill — in both the
collapsed launcher and the expanded panel — when a battery is actually
present. A desktop deployment with no battery hardware sees no pill at
all, rather than a fake `0%` or `100%`.

## Window model (unchanged from Stage 6)

Same single fixed-position, frameless, on-top pywebview window that
resizes rather than repositions — see Stage 6's own "Window model" note
for why. `set_panel("none" | "overlay")` grows/shrinks the window
between `BAR_HEIGHT` (the collapsed corner affordance) and
`PANEL_HEIGHT` (the full tile+content+slider panel); nothing below it
is obscured while collapsed.

## What's explicitly deferred

* **Immersive Mode auto-hide.** Stage 7 made the old topbar hide itself
  while Immersive Mode was on and the machine was online. That behavior
  has **not** been ported to `session/overlay/overlay.py` yet — see the
  note left in `docs/STAGE-7-VISIBILITY-AND-CURSOR.md`. Re-adding it is
  a straightforward port of Stage 7's `_visibility_watcher()`, but it's
  being tracked as an open item here rather than assumed to still work.
* **Compositor migration.** This stage does not change which
  compositor the overlay runs under — it still launches from
  `session/gnome-kiosk-script` under GNOME Kiosk today. See
  `docs/foundational/CAGE-MIGRATION.md` for the separate, staged plan
  to move to Cage; Stage 8 (this doc) is expected to finish first.
* **Picture / Sound / Bluetooth backends.** Deliberately out of scope
  for this stage — see the table above.

## Exit condition

A user can open the overlay from its corner affordance (or, on the
same hardware once Stage 3-equivalent input mapping is re-confirmed,
via remote/controller navigation), see accurate live Network,
brightness, and volume status, adjust brightness and volume, and view
or connect to a Wi-Fi network when Ethernet isn't in use — all without
a working network connection to reach the overlay itself. Selecting
Picture, Sound, or Bluetooth shows a clear "not built yet" placeholder
rather than a dead or broken control.
