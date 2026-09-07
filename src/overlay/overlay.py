#!/usr/bin/env python3
"""
overlay.py — Stage 8+cage-cutover: TV-style system overlay for the
ARKtube Webtop session.

Same overall design as Stage 8 (docs/STAGE-8-TV-STYLE-OVERLAY.md): a
small always-present status affordance that expands into a row of
large, remote-navigable tiles, styled after Google/Android TV's own
settings overlay. What changed in the cage cutover
(docs/foundational/CAGE-MIGRATION.md) is the window model, and it's a
real change, not a relabeling:

Stage 8 shipped this as a plain pywebview window with `x=0, y=0,
on_top=True` and relied on those GTK/X11-era window-manager hints to
keep it pinned above ARKtube. That doesn't work on Wayland: Wayland's
xdg_toplevel role deliberately gives clients no way to set their own
screen position or request to stay above other clients — that's not
GNOME Kiosk/Mutter being uncooperative, it's the protocol's own design,
specifically so one client can't place itself over another's UI. See
docs/foundational/CAGE-MIGRATION.md for the fuller writeup.

The actual Wayland primitive for "a small piece of always-on-top
compositor chrome" is wlr-layer-shell-v1, which cage did not previously
implement (see the cage fork's own layer_shell.c/.h for that half of
this change) and which this file now uses via gtk-layer-shell
(`gi.repository.GtkLayerShell`) instead of the old x=0/y=0/on_top
hints, hooked in through pywebview's public `window.events.before_show`
event — see attach_layer_shell() below. Those old hints are still
passed to webview.create_window() as a harmless, ignored fallback in
case this ever runs on a non-layer-shell Wayland compositor or the X11
xsessions path, but they are not what makes placement actually work
under cage any more.

Same non-negotiable as Stage 6/8: every control here resolves locally
through NetworkManager (nmcli), PipeWire/WirePlumber (wpctl) with a
PulseAudio (pactl) fallback, upower, brightnessctl, and loginctl/systemd.
Nothing in this file makes a network request or depends on one
succeeding.

Staging note (see docs/STAGE-8-TV-STYLE-OVERLAY.md): only the Network
(Wi-Fi/Ethernet) tile, and the always-visible brightness/volume
sliders, are real controls in this stage. The Picture, Sound, and
Bluetooth tiles are wired up in the UI and reachable by remote/keyboard
navigation, but their content panes are deliberately a "coming soon"
placeholder — see PLACEHOLDER_TILES below — until a later stage gives
each one an actual backend.
"""

import os
import logging
import signal
import subprocess
import sys
from pathlib import Path

import webview

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("GtkLayerShell", "0.1")
from gi.repository import GLib, Gtk, GtkLayerShell  # noqa: E402

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

# ---------------------------------------------------------------------------
# Logging. Sway launches this file via `exec` (see 20-arktube.conf), which
# does not give you a terminal to see stdout/stderr on -- a startup crash
# here previously just vanished, with no clue left behind that overlay.py
# never even got as far as creating a window. Everything of interest now
# also goes to a real file, and the top-level `try/except` in `__main__`
# below guarantees a traceback is written even for exceptions no other
# handler in this file catches.
# ---------------------------------------------------------------------------
LOG_PATH = HERE / "overlay.log"

logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    handlers=[
        logging.FileHandler(LOG_PATH),
        logging.StreamHandler(sys.stderr),
    ],
)
log = logging.getLogger("overlay")

# Window sizes for each panel state. Prior to the remote-input-mapping
# work (docs/planning/REMOTE-INPUT-MAPPING.md) there were only two:
# the collapsed top-right corner bar and the expanded top-right panel,
# both full-screen-width and differing only in height. Two more states
# were added alongside that doc's implementation:
#
#   - 'power': a small, centered Shut Down/Restart/Log Out menu,
#     opened by the remote's Power button (see main()'s SIGUSR2
#     handler) as well as by the existing power icon inside 'overlay'.
#   - 'osd': a small, centered, top-pinned transient toast for a
#     volume/brightness change -- not in the mapping doc, added
#     alongside it for the same "ARKTUBE"-branded on-screen-display
#     look real TVs show on a remote volume/brightness press.
#
# Both of the new states are centered rather than top-right, so they
# need their own width *and* height, not just a height change against
# the same full-screen width -- see PANEL_GEOMETRY below.
BAR_HEIGHT = 56
PANEL_HEIGHT = 620
POWER_MENU_WIDTH = 420
POWER_MENU_HEIGHT = 300
OSD_WIDTH = 320
OSD_HEIGHT = 210

# Tiles that exist in the UI this stage, but have no real backend yet.
PLACEHOLDER_TILES = {"bluetooth", "sound", "picture"}

# Immersive-Mode-gated auto-hide (Stage 7, restored by Stage 8) is
# intentionally *not* here. That mechanism hid the launcher affordance
# while `main` was both in "Immersive Mode" and fully connected, by
# reading a flag `main` was expected to persist to
# `immersiveMode.neustorage` (a Neutralino.storage artifact). Current
# `main` (`arktube_linux`, a GTK3 + WebKit2GTK rewrite -- see its own
# README's "Not yet ported" list) never writes that file: it has no
# Neutralino dependency left and Immersive Mode itself was never
# ported. Reading a file that's never written meant
# `_immersive_mode_enabled()` always returned False, which always
# satisfied the auto-hide's "not in Immersive Mode" branch -- so in
# practice the launcher simply never auto-hid, silently, with no error.
#
# Rather than resurrect a signal `main` doesn't produce, this overlay
# no longer waits on one at all: this window is a real
# wlr-layer-shell-v1 surface pinned above ARKtube's own surface (see
# attach_layer_shell() below), not an in-page element that needs the
# app's cooperation to be seen or interacted with. An overlay, by
# construction, overlays -- ARKtube never has to "exit" anything for
# the launcher to be visible or clickable, so there's nothing for a
# same-session app to signal in the first place. The launcher is just
# always shown; see SystemAPI.__init__ and main() below, which no
# longer start a visibility-watcher thread.


def run(cmd, timeout=3):
    """Run a local command, return stripped stdout, or None on any failure.

    Every caller here treats None as "control unavailable" and degrades
    the UI rather than raising.
    """
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout, check=False
        )
        return result.stdout.strip() if result.returncode == 0 else None
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None


# ---------------------------------------------------------------------------
# gtk-layer-shell wiring.
#
# gtk-layer-shell's contract is that GtkLayerShell.init_for_window() must
# run before the window is shown (realized). pywebview exposes exactly
# that moment as a public, documented event -- window.events.before_show,
# fired at the end of BrowserView.__init__() in
# webview/platforms/gtk.py, once the underlying Gtk window exists
# (window.native is already set by then) but strictly before
# BrowserView.show()'s show_all() call. That means this only needs
# pywebview's public API, not a monkey-patch of its internals: attach a
# before_show handler per window, and it runs at the right time.
# ---------------------------------------------------------------------------


def attach_layer_shell(window, *, layer, anchors, exclusive_zone, keyboard_mode):
    """Arrange for `window` to be initialized as a wlr-layer-shell-v1
    surface the moment it's about to be shown, instead of relying on
    pywebview's x=0/y=0/on_top hints, which do nothing under Wayland
    (see the module docstring). Must be called before webview.start().
    """
    config = {
        "layer": layer,
        "anchors": tuple(anchors),
        "exclusive_zone": exclusive_zone,
        "keyboard_mode": keyboard_mode,
    }

    def _on_before_show():
        gtk_window = getattr(window, "native", None)
        if gtk_window is None:
            log.error(
                "window.native was unset in before_show; cannot "
                "initialize gtk-layer-shell. This window will fall back "
                "to unmanaged placement, which is the Stage 8 bug this "
                "file exists to fix. (Is pywebview actually running its "
                "GTK backend? webview.start() must be called with "
                "gui=\"gtk\".)"
            )
            return
        try:
            _init_layer_shell(gtk_window, config)
        except Exception:  # noqa: BLE001 - fail loud, not silent; see above
            log.exception(
                "gtk-layer-shell init failed. Is gir1.2-gtklayershell-0.1 "
                "/ libgtk-layer-shell0 installed? Falling back to "
                "unmanaged placement."
            )

    window.events.before_show += _on_before_show


def _init_layer_shell(gtk_window, config):
    """First-time setup only -- call exactly once per window, before it
    is shown (attach_layer_shell()'s before_show handler does this).
    See _update_layer_shell() for changing an already-shown window's
    layer/anchors/etc. at runtime (what lock()/unlock() need):
    gtk-layer-shell's init_for_window() is a one-time role transition,
    not something safe to call again later.
    """
    GtkLayerShell.init_for_window(gtk_window)
    _update_layer_shell(gtk_window, config)


def _update_layer_shell(gtk_window, config):
    """Apply layer/anchors/margins/exclusive-zone/keyboard-mode to a
    window that has already had init_for_window() called on it once.
    Safe to call repeatedly -- these are plain runtime setters, unlike
    init_for_window() itself."""
    GtkLayerShell.set_layer(gtk_window, config["layer"])
    for edge in (
        GtkLayerShell.Edge.TOP,
        GtkLayerShell.Edge.BOTTOM,
        GtkLayerShell.Edge.LEFT,
        GtkLayerShell.Edge.RIGHT,
    ):
        GtkLayerShell.set_anchor(gtk_window, edge, edge in config["anchors"])
        GtkLayerShell.set_margin(gtk_window, edge, 0)
    GtkLayerShell.set_exclusive_zone(gtk_window, config["exclusive_zone"])
    GtkLayerShell.set_keyboard_mode(gtk_window, config["keyboard_mode"])


# Per-panel geometry: size and layer-shell anchors, looked up by the
# panel name set_panel() receives. 'none'/'overlay' stay anchored
# (TOP, RIGHT) at the full screen width, same as before this table
# existed. 'power' and 'osd' anchor to fewer edges on purpose --
# gtk-layer-shell centers a surface on any axis where neither of that
# axis's edges is anchored (see attach_layer_shell()'s module
# docstring), which is what puts 'power' dead-center and 'osd'
# horizontally centered near the top, without either of them needing
# to know the screen's actual width the way the full-width states do.
#
# `size` is a callable (not a plain tuple) only so 'none'/'overlay'
# can read api.width, which isn't known until main() queries the real
# screen -- everything else here is a fixed constant.
PANEL_GEOMETRY = {
    "none": {
        "size": lambda api: (api.width, BAR_HEIGHT),
        "anchors": (GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT),
        "keyboard_mode": GtkLayerShell.KeyboardMode.ON_DEMAND,
    },
    "overlay": {
        "size": lambda api: (api.width, PANEL_HEIGHT),
        "anchors": (GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT),
        # EXCLUSIVE, not ON_DEMAND -- see REMOTE-INPUT-MAPPING.md's
        # "The keyboard-focus gap". Becoming visible doesn't grant a
        # Wayland surface keyboard focus by itself; something has to
        # claim it, the same way lock()/unlock() already do for the
        # lock screen. Without this, the D-Pad would keep going to
        # ARKtube underneath the open panel instead of the panel
        # itself -- exactly backwards from the reference behavior.
        "keyboard_mode": GtkLayerShell.KeyboardMode.EXCLUSIVE,
    },
    "power": {
        "size": lambda api: (POWER_MENU_WIDTH, POWER_MENU_HEIGHT),
        "anchors": (),
        "keyboard_mode": GtkLayerShell.KeyboardMode.EXCLUSIVE,
    },
    "osd": {
        "size": lambda api: (OSD_WIDTH, OSD_HEIGHT),
        "anchors": (GtkLayerShell.Edge.TOP,),
        # No keyboard interaction happens on the toast, so this stays
        # ON_DEMAND rather than claiming focus away from whatever
        # already has it (usually ARKtube itself, mid-playback).
        "keyboard_mode": GtkLayerShell.KeyboardMode.ON_DEMAND,
    },
}


class SystemAPI:
    """
    JS-callable bridge exposed to static/app.js as `pywebview.api.*`.
    """

    def __init__(self):
        self.window = None
        self.width = 1920  # overwritten in main() from the real screen
        self.locked = False

    # ---- panel state ------------------------------------------------------

    def set_panel(self, panel):
        """Resize/reposition the window for the requested panel state:
        'none' (collapsed corner bar), 'overlay' (the full settings
        panel), 'power' (the new centered Shut Down/Restart/Log Out
        menu), or 'osd' (the transient volume/brightness toast). See
        PANEL_GEOMETRY above for what each actually looks like.
        Ignored while locked -- the lock screen owns the window's
        geometry until unlock() runs.

        This is called from two different places: JS, via the normal
        pywebview.api bridge (a background thread, per pywebview's own
        threading model) when the user clicks/keys something in the
        DOM; and this file's own SIGUSR1/SIGUSR2 handlers in main()
        (see there), when the Menu or Power button on the remote fires
        a Sway bindsym instead. Both need the same two things done
        somewhere that's safe to touch GTK from -- resize the window
        and update its gtk-layer-shell anchors/keyboard-mode -- so
        both paths funnel through here rather than each doing it
        themselves. GLib.idle_add marshals onto the GTK main loop
        either way, same as lock()/unlock() already do; nesting an
        idle_add call inside one that's already running on that loop
        (the SIGUSR* path pre-wraps its call, see main()) is harmless,
        it just queues one more iteration.

        Once the geometry is applied, this pushes the same panel name
        into JS via window.__cgSetPanel (see static/app.js), which is
        what actually shows/hides the matching DOM -- this file has no
        DOM of its own to update. That single push is now the *only*
        place DOM visibility for these four states changes; JS's own
        openPanel()/closePanel() no longer touch the DOM directly,
        they just call this and wait for the push back. That also
        means a signal-triggered open (Menu/Power) and a click-
        triggered open converge on identical behavior instead of the
        DOM having two different code paths to get into the same
        visible state.
        """
        if self.locked:
            return "locked"
        geometry = PANEL_GEOMETRY.get(panel, PANEL_GEOMETRY["none"])

        def apply():
            if self.window is None:
                return
            width, height = geometry["size"](self)
            gtk_window = getattr(self.window, "native", None)
            if gtk_window is not None:
                _update_layer_shell(
                    gtk_window,
                    {
                        "layer": GtkLayerShell.Layer.OVERLAY,
                        "anchors": geometry["anchors"],
                        "exclusive_zone": -1,
                        "keyboard_mode": geometry["keyboard_mode"],
                    },
                )
            self.window.resize(width, height)
            self.window.evaluate_js(
                f"window.__cgSetPanel && window.__cgSetPanel({panel!r})"
            )

        GLib.idle_add(apply)
        return panel

    # ---- status polling -----------------------------------------------------

    def get_status(self):
        battery = self._battery()
        return {
            "network": self._network(),
            "volume": self._volume(),
            "brightness": self._brightness(),
            "battery": battery,
            "is_laptop": battery is not None,
            "locked": self.locked,
        }

    def _devices(self):
        out = run(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "device", "status"]) or ""
        devices = []
        for line in out.splitlines():
            parts = line.split(":")
            if len(parts) >= 3:
                devices.append({"device": parts[0], "type": parts[1], "state": parts[2]})
        return devices

    def _wifi(self):
        out = run(["nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi"])
        if out is None:
            return {"available": False, "connected": False, "ssid": None}
        for line in out.splitlines():
            fields = line.split(":", 1)
            active = fields[0]
            ssid = fields[1] if len(fields) > 1 else ""
            if active == "yes":
                return {"available": True, "connected": True, "ssid": ssid}
        return {"available": True, "connected": False, "ssid": None}

    def _connectivity_full(self):
        # Plain cached read, not `... connectivity check` -- must never
        # block waiting on a fresh probe, since it backs a status poll
        # on a timer.
        out = run(["nmcli", "networking", "connectivity"])
        return out is not None and out.strip().lower() == "full"

    def _network(self, wifi=None):
        """Ethernet-priority network status. Accepts an already-fetched
        `wifi` dict so callers that already have one (e.g.
        network_toggle_wifi_radio()) don't make _wifi() shell out to
        nmcli a second time in the same request -- see the bug report's
        item 6: this used to call self._wifi() and then call
        self._network() internally, which called _wifi() again."""
        devices = self._devices()
        ethernet_connected = any(
            d["type"] == "ethernet" and d["state"] == "connected" for d in devices
        )
        if wifi is None:
            wifi = self._wifi()
        stable = self._connectivity_full()

        if ethernet_connected and stable:
            return {
                "type": "ethernet",
                "label": "Ethernet",
                "sub": "Connected",
                "connected": True,
                "wifi_radio_on": wifi["available"],
            }

        if ethernet_connected and not stable:
            if wifi["connected"]:
                return {
                    "type": "wifi",
                    "label": "Wi-Fi",
                    "sub": wifi["ssid"] or "Connected",
                    "connected": True,
                    "fallback_from_ethernet": True,
                    "wifi_radio_on": True,
                }
            return {
                "type": "ethernet",
                "label": "Ethernet",
                "sub": "Unstable",
                "connected": True,
                "unstable": True,
                "wifi_radio_on": wifi["available"],
            }

        if wifi["connected"]:
            return {
                "type": "wifi",
                "label": "Wi-Fi",
                "sub": wifi["ssid"] or "Connected",
                "connected": True,
                "wifi_radio_on": True,
            }

        return {
            "type": "none",
            "label": "Network",
            "sub": "Not Connected",
            "connected": False,
            "wifi_radio_on": wifi["available"],
        }

    def _volume(self):
        out = run(["wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"])
        if out and "Volume:" in out:
            try:
                level = round(float(out.split()[1]) * 100)
                return {"level": level, "muted": "MUTED" in out, "available": True}
            except (IndexError, ValueError):
                pass
        out = run(["pactl", "get-sink-volume", "@DEFAULT_SINK@"])
        if out and "%" in out:
            try:
                pct = int(out.split("/")[1].strip().rstrip("%"))
                muted_out = run(["pactl", "get-sink-mute", "@DEFAULT_SINK@"]) or ""
                return {"level": pct, "muted": "yes" in muted_out, "available": True}
            except (IndexError, ValueError):
                pass
        return {"level": 0, "muted": True, "available": False}

    def _brightness(self):
        current = run(["brightnessctl", "get"])
        maximum = run(["brightnessctl", "max"])
        if current and maximum and maximum.isdigit() and int(maximum) > 0:
            return {"level": round(int(current) / int(maximum) * 100), "available": True}
        return {"level": 0, "available": False}

    def _battery(self):
        devices = run(["upower", "-e"]) or ""
        battery_path = next(
            (line for line in devices.splitlines() if "battery" in line), None
        )
        if not battery_path:
            return None
        out = run(["upower", "-i", battery_path])
        if not out:
            return None
        percent, state = None, None
        for line in out.splitlines():
            line = line.strip()
            if line.startswith("percentage:"):
                percent = line.split(":", 1)[1].strip().rstrip("%")
            elif line.startswith("state:"):
                state = line.split(":", 1)[1].strip()
        if percent and percent.isdigit():
            return {"percent": int(percent), "charging": state == "charging"}
        return None

    # ---- essentials: sliders -----------------------------------------------

    def set_volume(self, level):
        level = max(0, min(100, int(level)))
        if run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{level}%"]) is None:
            run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"])
        return self._volume()

    def toggle_mute(self):
        if run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]) is None:
            run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"])
        return self._volume()

    def set_brightness(self, level):
        level = max(1, min(100, int(level)))
        run(["brightnessctl", "set", f"{level}%"])
        return self._brightness()

    # ---- essentials: network tile ------------------------------------------

    def network_scan(self):
        out = run(["nmcli", "-t", "-f", "IN-USE,SSID,SIGNAL,SECURITY", "dev", "wifi", "list"])
        if out is None:
            return []
        best = {}
        for line in out.splitlines():
            fields = line.split(":", 3)
            if len(fields) < 4:
                continue
            in_use, ssid, signal, security = fields
            if not ssid:
                continue
            try:
                signal = int(signal)
            except ValueError:
                signal = 0
            entry = {
                "ssid": ssid,
                "signal": signal,
                "secured": bool(security.strip()),
                "in_use": in_use.strip() == "*",
            }
            if ssid not in best or signal > best[ssid]["signal"]:
                best[ssid] = entry
        return sorted(best.values(), key=lambda e: e["signal"], reverse=True)

    def network_toggle_wifi_radio(self):
        wifi = self._wifi()
        current = self._network(wifi=wifi)
        turning_on = not wifi.get("available") or not current.get("wifi_radio_on", True)
        run(["nmcli", "radio", "wifi", "on" if turning_on else "off"])
        # Radio state just changed, so this one re-query is real work,
        # not the redundant one the bug report flagged -- that was
        # _network() calling _wifi() a *second* time internally on the
        # exact same, already-fetched status, above.
        return self._network()

    def network_connect(self, ssid, password=""):
        cmd = ["nmcli", "dev", "wifi", "connect", ssid]
        if password:
            cmd += ["password", password]
        try:
            result = subprocess.run(
                cmd, capture_output=True, text=True, timeout=20, check=False
            )
            success = result.returncode == 0
        except (subprocess.TimeoutExpired, FileNotFoundError):
            success = False
        return {"success": success, "network": self._network()}

    # ---- session lifecycle --------------------------------------------------

    def lock(self):
        """Actually lock the session, not just flip a flag nothing
        reads. Previously (see the bug report) this called
        `loginctl lock-session` and stopped there: that only sets
        LockedHint, a status flag other tools can read -- GNOME Kiosk
        ships no ScreenShield to read it, so nothing enforced anything
        and ARKtube stayed fully interactive underneath a "Lock" button
        that had done nothing.

        This still calls `loginctl lock-session` first, so LockedHint
        stays accurate for any *other* tooling that inspects it, but
        the actual enforcement is now this overlay's own layer surface:
        anchored to all four edges (full-output coverage) with
        keyboard_mode=EXCLUSIVE, so ARKtube underneath can receive
        neither keyboard nor (once the frontend's lock pane covers the
        screen) pointer input until unlock() runs. There is no PIN or
        credential check here -- this is a single-user TV appliance
        with no auth backend defined anywhere else in this project, and
        implementing one wasn't part of the bug being fixed. What was
        broken, and is fixed now, is that pressing Lock does something:
        ARKtube is genuinely no longer reachable until Unlock.
        """
        run(["loginctl", "lock-session"])
        self.locked = True

        def apply():
            if self.window is not None:
                gtk_window = getattr(self.window, "native", None)
                if gtk_window is not None:
                    _update_layer_shell(
                        gtk_window,
                        {
                            "layer": GtkLayerShell.Layer.OVERLAY,
                            "anchors": (
                                GtkLayerShell.Edge.TOP,
                                GtkLayerShell.Edge.BOTTOM,
                                GtkLayerShell.Edge.LEFT,
                                GtkLayerShell.Edge.RIGHT,
                            ),
                            "exclusive_zone": 0,
                            "keyboard_mode": GtkLayerShell.KeyboardMode.EXCLUSIVE,
                        },
                    )
                self.window.evaluate_js("window.__cgSetLocked && window.__cgSetLocked(true)")

        GLib.idle_add(apply)
        return {"locked": True}

    def unlock(self):
        """Reverse lock(): restore the normal top-right anchored strip
        and hand keyboard interactivity back to on-demand."""
        run(["loginctl", "unlock-session"])
        self.locked = False

        def apply():
            if self.window is not None:
                gtk_window = getattr(self.window, "native", None)
                if gtk_window is not None:
                    _update_layer_shell(
                        gtk_window,
                        {
                            "layer": GtkLayerShell.Layer.OVERLAY,
                            "anchors": (GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT),
                            "exclusive_zone": -1,
                            "keyboard_mode": GtkLayerShell.KeyboardMode.ON_DEMAND,
                        },
                    )
                self.window.resize(self.width, BAR_HEIGHT)
                self.window.evaluate_js("window.__cgSetLocked && window.__cgSetLocked(false)")

        GLib.idle_add(apply)
        return {"locked": False}

    def logout(self):
        """Log out of the session.

        Previously (see the bug report, items 3a/3b) this had two
        problems:

        - If `loginctl show-session self -p Id --value` ever came back
          empty (any transient nonzero exit, not just "no session" --
          see run()'s own contract), the fallback called
          `loginctl terminate-user ""` -- an empty username -- which
          just fails silently.
        - `loginctl terminate-session` is a forced kill of the
          session's processes from systemd-logind, not a graceful
          request through a session manager's own shutdown path. Under
          GNOME Kiosk that mattered: the project's own Stage 2 research
          (docs/STAGE-2-SESSION-LIFECYCLE.md) had already identified
          `gnome-session-quit --logout --no-prompt` as the correct
          primitive, and this bypassed it.

        Post-cutover (this branch runs under Sway, not the earlier cage
        fork -- see docs/foundational/SYSTEM_DESIGN.md), the second
        problem no longer applies in a different way than it briefly
        did under cage: `loginctl terminate-session` against systemd-
        logind kills every process in the session's scope, Sway
        included, regardless of which process happens to ask for it.
        There is no separate gnome-session layer for this call to
        bypass, so it's the right primitive here, not a shortcut past
        a better one. (An earlier version of this comment described
        this in terms of the removed cage fork's own sigchld_handler()
        exiting when its direct child exited -- that file no longer
        exists in this branch and was never accurate for Sway, which
        does not shape its exit behavior around a single tracked child
        the way cage did. Sway's own session-readiness signaling, and
        what stops `sway-session.target` on exit, is
        src/session/sway/config.d/10-systemd.conf, not anything in
        this file.)

        The first problem is fixed properly here: prefer
        $XDG_SESSION_ID, which systemd-logind's pam_systemd already
        exports into every login session's environment (so it doesn't
        depend on `show-session self`'s systemd-233+ "self" alias
        working); fall back to the `show-session self` query only if
        that's unset; and if *both* are unavailable, fall back to
        closing our own window rather than ever calling
        terminate-session/terminate-user with an empty argument --
        with no other windows/output left and this being Sway's one
        client, Sway itself exits and 10-systemd.conf's own shutdown
        subscription stops sway-session.target, ending the session
        anyway, just one step later than a direct terminate-session
        call would.
        """
        session_id = os.environ.get("XDG_SESSION_ID") or run(
            ["loginctl", "show-session", "self", "-p", "Id", "--value"]
        )
        if session_id:
            run(["loginctl", "terminate-session", session_id])
            return
        if self.window is not None:
            self.window.destroy()

    def poweroff(self):
        run(["systemctl", "poweroff"])

    def reboot(self):
        run(["systemctl", "reboot"])

    # No immersive-mode auto-hide, and no visibility-watcher thread --
    # see the module-level comment above PLACEHOLDER_TILES for why. The
    # launcher is always shown; nothing in SystemAPI needs to poll for
    # or toggle that.


def _screen_width(default=1920):
    try:
        screens = webview.screens
        if screens:
            return screens[0].width
    except Exception:
        pass
    return default


def main():
    api = SystemAPI()
    api.width = _screen_width()

    window = webview.create_window(
        "ARKtube Overlay",
        url=str(STATIC / "index.html"),
        js_api=api,
        width=api.width,
        height=BAR_HEIGHT,
        # Harmless fallback hints, not the real fix -- see the module
        # docstring. Real placement is gtk-layer-shell, below.
        x=0,
        y=0,
        frameless=True,
        on_top=True,
        transparent=True,
        # MUST be True, not False. pywebview's GTK backend
        # (webview/platforms/gtk.py, BrowserView.__init__) branches on
        # this at window-construction time, before gtk-layer-shell ever
        # gets involved:
        #
        #   if window.resizable:
        #       self.window.set_size_request(*window.min_size)
        #       self.window.resize(window.initial_width, window.initial_height)
        #   else:
        #       self.window.set_size_request(window.initial_width, window.initial_height)
        #
        # With resizable=False (what this was), that `else` locks
        # GTK's minimum-size floor to this call's own width/height --
        # (api.width, BAR_HEIGHT), i.e. the full-width 56px corner bar
        # -- permanently. set_panel()'s later self.window.resize()
        # calls (the only thing that ever runs for 'osd'/'power'/
        # 'overlay') never touch that floor again, so GTK keeps
        # re-clamping the window back toward it: the 320x210 OSD toast
        # and 420x300 power menu can never actually shrink to their
        # real size, and fighting that floor against whatever the
        # compositor negotiates for the layer-shell surface is what
        # was taking overlay.py down entirely on the first Power
        # press (see PANEL_GEOMETRY['power'] below and
        # docs/planning/REMOTE-INPUT-MAPPING.md) -- which is also why
        # a second physical Power press reached logind's own default
        # handler and shut the machine down: nothing was left running
        # to hold the `systemd-inhibit --what=handle-power-key` lock
        # 20-arktube.conf now execs this file through.
        #
        # resizable=True takes the `if` branch above instead, which
        # sets the floor from `min_size` -- explicitly given below as
        # the smallest width and height any real panel state uses
        # (OSD_WIDTH x BAR_HEIGHT), not pywebview's own default
        # (200x100, which is itself taller than BAR_HEIGHT and would
        # wrongly floor the collapsed corner bar). That floor is at or
        # below every PANEL_GEOMETRY entry on both axes, so it never
        # clamps any of them upward, and self.window.resize() actually
        # takes effect afterwards the way set_panel() expects.
        resizable=True,
        min_size=(OSD_WIDTH, BAR_HEIGHT),
        easy_drag=False,
    )
    attach_layer_shell(
        window,
        layer=GtkLayerShell.Layer.OVERLAY,
        anchors=(GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT),
        exclusive_zone=-1,
        keyboard_mode=GtkLayerShell.KeyboardMode.ON_DEMAND,
    )
    api.window = window

    # Remote-triggered panel opens (docs/planning/REMOTE-INPUT-MAPPING.md,
    # "Menu -> open the overlay" / "Power -> a new centered panel").
    # overlay.py is a separate process from Sway, so a `bindsym` in
    # 20-arktube.conf can only `exec` something -- it can't call
    # set_panel() on this already-running process directly. A POSIX
    # signal is the cheapest bridge: Sway execs `pkill -SIGUSR1 -f
    # overlay.py` (or SIGUSR2 for Power), this process's signal
    # handler fires on some arbitrary thread, and GLib.idle_add
    # marshals the actual set_panel() call onto the GTK main loop --
    # signal handlers in Python can run at essentially any point, and
    # GTK/WebKit calls are only safe from their own main loop thread.
    signal.signal(
        signal.SIGUSR1,
        lambda *_: GLib.idle_add(lambda: api.set_panel("overlay")),
    )
    signal.signal(
        signal.SIGUSR2,
        lambda *_: GLib.idle_add(lambda: api.set_panel("power")),
    )

    webview.start(gui="gtk")


if __name__ == "__main__":
    log.info("overlay.py starting (log file: %s)", LOG_PATH)
    try:
        main()
    except Exception:
        log.exception(
            "overlay.py crashed during startup -- this is almost "
            "certainly why the overlay/OSD/menu never appeared. See the "
            "traceback above/in %s for the real cause.",
            LOG_PATH,
        )
        raise
