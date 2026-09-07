# Remote input mapping — Fire TV reference

**Status:** Implemented. Menu/Power/Volume bindsyms and cursor
auto-hide are in `src/session/sway/config.d/20-arktube.conf`;
`set_panel()`'s generalized keyboard-focus handling, the SIGUSR1/
SIGUSR2 handlers, and the new centered `'power'` panel state are in
`src/overlay/overlay.py` and `src/overlay/static/`. The keysyms for
Menu and Power, and whether Volume/Power reach this box as Bluetooth
HID at all on the specific remote being paired, are still flagged
`[ ]` below — see 20-arktube.conf's own comments for what was
confirmed by research versus what still needs a `wev`/`evtest` check
against real hardware.
**Built on:** `docs/foundational/SYSTEM_DESIGN.md` (Sway layer),
`src/overlay/overlay.py` (the salvaged Stage 8 overlay), `webtop`'s
`docs/STAGE-3-INPUT-MAPPING.md` (the methodology this doc follows —
confirm keysyms directly, don't assume).
**Reference device:** a Fire TV remote, used here as the concrete
spec for what "TV remote" means, not because ARKtube is Fire-TV-
specific.

## The reference behavior

| Button | Expected behavior |
|---|---|
| D-Pad | Passed wholesale to ARKtube. Once an overlay is open, passed to whichever surface is focused instead. |
| Home | ARKtube's own default handling. |
| Search | Passed to the focused app (ARKtube, for now). |
| Menu (☰) | Opens the overlay. |
| Power | Opens a **centered** power menu — Shut Down / Restart / Log Out. |
| Play / Pause / Next / Prev | Standard media-key behavior. |
| Vol +/− / Mute | Standard volume behavior. |

## Where each button's logic actually lives

| Button | Owner | Mechanism | Already true, or new work? |
|---|---|---|---|
| D-Pad → ARKtube | ARKtube | No `bindsym` in Sway — passthrough is the *absence* of a binding | **Already true.** Same "checked directly, not assumed" finding `webtop`'s Stage 3 made for GNOME Kiosk's arrow/Enter/Escape/Home/F11 set; not yet re-verified against Sway's own default config the same way. |
| D-Pad → focused overlay/power-menu once open | overlay.py + Sway | Requires the surface that opened to actually hold keyboard focus | **Done.** `set_panel()` now sets `keyboard_mode=EXCLUSIVE` for `'overlay'`/`'power'` and `ON_DEMAND` for `'none'`/`'osd'` — see overlay.py's `PANEL_GEOMETRY`. |
| Home | ARKtube | `app-init.js`'s existing `keydown` listener | **Already true**, per Stage 3. |
| Search | ARKtube | No binding — passthrough | **Already true**, nothing to add. |
| Play/Pause/Next/Prev | ARKtube (browser Media Session API) | No binding — passthrough of `XF86Audio*` keys | **Probably already true**, not yet confirmed against real hardware. |
| Vol +/−/Mute | Sway | `bindsym` → `wpctl`, overlay polls and reflects the result | **Done** — see 20-arktube.conf. Whether the paired remote's Volume/Mute buttons reach this box as Bluetooth HID at all is genuinely remote-model-dependent — see that file's own comment. |
| Menu | overlay.py | `bindsym` → signal → `set_panel("overlay")` | **Done** — see 20-arktube.conf and overlay.py's `main()`. Keysym still unconfirmed against real hardware; two candidates bound defensively. |
| Power | overlay.py | `bindsym` → signal → a **new** centered panel state | **Done** — see `PANEL_GEOMETRY['power']`, `static/index.html`'s `#power-panel`, and `static/app.js`. |

## Volume — config only

Bypass `overlay.py` for the actual change; it already polls `wpctl` on
every status refresh, so the on-screen pill just reflects whatever Sway
set.

```
# src/session/sway/config.d/20-arktube.conf (addition)
bindsym --locked XF86AudioRaiseVolume exec wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+
bindsym --locked XF86AudioLowerVolume exec wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%-
bindsym --locked XF86AudioMute        exec wpctl set-mute @DEFAULT_AUDIO_SINK@ toggle
```

`--locked` so these still fire while `overlay.py`'s own lock state
(`keyboard_mode=EXCLUSIVE`) is active — volume arguably should work even
when locked, the same way it does on a real TV.

**Researched, not just assumed:** the standard `KEY_VOLUMEUP`/`DOWN`/
`MUTE` evdev codes map straight through XKB's stock `evdev` keycodes
file to these exact `XF86Audio*` keysyms — that part isn't in
question. What genuinely varies by remote model, confirmed by public
`evtest` dumps rather than assumed: a first-generation Amazon Fire TV
Remote (bus 0x5, vendor 0x1949, product 0x404) has no physical Volume/
Mute buttons at all, and Alexa Voice Remotes that do add them commonly
route those buttons to the *TV* via IR/HDMI-CEC rather than as
Bluetooth HID events to whatever they're paired with — Amazon's own
third-party-app documentation confirms these "can't be mapped to
events" a non-Fire-TV app receives. Confirm which case applies to the
actual remote/dongle being paired with `wev` or `evtest` before
relying on this.

## Menu → open the overlay

`overlay.py` is a separate pywebview process from Sway; a `bindsym`
can't call `set_panel()` directly. Cheapest bridge is a POSIX signal:

```python
# overlay.py — new, near SystemAPI.__init__ / main()
import signal
signal.signal(
    signal.SIGUSR1,
    lambda *_: GLib.idle_add(lambda: api.set_panel("overlay")),
)
```

```
# 20-arktube.conf (addition)
bindsym --locked Menu exec pkill -SIGUSR1 -f overlay.py
```

**Researched:** three independent public `evtest` dumps of an actual
Bluetooth "Amazon Fire TV Remote" (same bus/vendor/product across all
three) agree its ☰ button reports raw evdev `KEY_MENU` (code 139),
and a separate hands-on `xinput --test` session against the same
remote independently arrived at X11 keycode 147 (139+8, XKB's evdev
offset) for this button — consistent with each other. **Still
unconfirmed:** the actual keysym name XKB's default symbols bind to
that keycode in this project's own Sway environment. Both `Menu` and
`XF86Menu` are bound defensively above rather than guessing one — an
unmatched bindsym is inert, not harmful. Confirm with `wev` before
trusting either exclusively.

## Power → a new centered panel

Two separate gaps here, not one:

### 1. New UI state, not a new binding

The existing overlay only has a power *icon* inside the corner panel
(`set_panel('none' | 'overlay')`). The reference behavior wants a third,
**centered** state — Shut Down / Restart / Log Out — which needs:

- A new `panel` value, e.g. `set_panel('power')`, sized/positioned
  differently from the corner strip.
- A new `attach_layer_shell()` call (or a runtime `_update_layer_shell()`
  swap) with **no anchors set** — anchoring to zero edges is what
  centers a layer-shell surface, versus the current overlay's
  `(TOP, RIGHT)` anchors.
- New markup/JS in `static/index.html` / `static/app.js` for the
  Shut Down / Restart / Log Out choices, wired to the SystemAPI methods
  `overlay.py` already has: `poweroff()`, `reboot()`, `logout()`.

```
# 20-arktube.conf (addition)
bindsym --locked XF86PowerOff exec pkill -SIGUSR2 -f overlay.py
```

```python
# overlay.py — SIGUSR2 opens the power menu instead of the overlay
signal.signal(
    signal.SIGUSR2,
    lambda *_: GLib.idle_add(lambda: api.set_panel("power")),
)
```

**Researched, and genuinely remote-model-dependent** (unlike Menu
above, where the ambiguity is only in the keysym name): the same
first-generation Fire TV Remote that lacks Volume/Mute buttons also
has no Power button at all. Where a Power button does exist on a
given remote, `KEY_POWER` (evdev 116) maps cleanly to `XF86PowerOff`
in XKB's stock tables — confirmed via a public `evtest` dump of an
NVIDIA Shield remote's Bluetooth HID reports, and independently via a
generic keyboard's Power key on a different forum thread doing the
same `xmodmap -pk` check. But per the Volume section above, a
Power button that *does* exist may still route to the TV via IR/CEC
rather than reach this box at all. Confirm against the actual
hardware being paired.

### 2. The keyboard-focus gap

This is the one genuine bug uncovered by working through the D-Pad
rule, not just an unverified assumption:

> *"D-Pad is passed wholesale, until an overlay appears on screen. At
> that point the D-Pad is passed to the currently focused window."*

Right now, only `lock()` / `unlock()` touch `keyboard_mode`
(flipping to `EXCLUSIVE` and back to `ON_DEMAND`). `set_panel()` — which
is what both Menu and Power would call — doesn't touch keyboard mode at
all. Under Wayland, a surface becoming *visible* doesn't automatically
grant it keyboard focus; something has to explicitly request it. So as
written today, opening the overlay or a power menu wouldn't actually
pull the D-Pad onto it — it'd keep going to ARKtube underneath, which
is exactly backwards from the reference behavior.

**Fix, implemented:** `set_panel()`'s `PANEL_GEOMETRY` table now
carries a `keyboard_mode` per panel state — `EXCLUSIVE` for
`'overlay'`/`'power'`, `ON_DEMAND` for `'none'`/`'osd'` — applied every
time the panel changes, the same transition `lock()`/`unlock()` already
made for the lock screen, just table-driven across all four states
instead of hardcoded for two.

## Cursor auto-hide

Sway has this natively — no `unclutter`, no X11-only caveat like
`webtop` needed:

```
# src/session/sway/config.d/20-arktube.conf (addition)
seat seat0 hide_cursor 3000
seat seat0 hide_cursor when-typing enable
```

## Open verification items

All of the following still need to be checked against real hardware
— consistent with this project's own standard of confirming against
the actual installed/actual device rather than assuming:

- [ ] Keysym the Fire TV remote's Menu (☰) button actually emits
      (narrowed to X11 keycode 147 by research; keysym name at that
      keycode not yet confirmed — both `Menu` and `XF86Menu` are
      bound defensively in the meantime)
- [ ] Whether the specific remote/dongle being paired delivers
      Power/Volume/Mute to this box as Bluetooth HID at all, or only
      drives the TV via IR/HDMI-CEC (confirmed to vary by remote
      model — see the Volume and Power sections above)
- [ ] Whether Play/Pause/Next/Prev arrive as standard `XF86Audio*` keys
      or something vendor-specific
- [ ] That the keyboard-focus fix above actually moves D-Pad input onto
      the overlay/power-menu surface once open, and back to ARKtube once
      closed
