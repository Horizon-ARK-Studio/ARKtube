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
import subprocess
import threading
import time
from pathlib import Path

import webview

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("GtkLayerShell", "0.1")
from gi.repository import GLib, Gtk, GtkLayerShell  # noqa: E402

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

# Window heights for each panel state. The window itself is still a
# single fixed-width strip that grows/shrinks downward from the top-
# right corner, same as Stage 6/8 — what's different post-cutover is
# *why* it stays above ARKtube and pinned to that corner (see module
# docstring: gtk-layer-shell, not x=0/y=0/on_top).
BAR_HEIGHT = 56
PANEL_HEIGHT = 620

# Tiles that exist in the UI this stage, but have no real backend yet.
PLACEHOLDER_TILES = {"bluetooth", "sound", "picture"}

STATUS_POLL_SECONDS = 2

# main's own source of truth for Immersive Mode -- see
# docs/STAGE-7-VISIBILITY-AND-CURSOR.md, which this restores the
# behavior of (Stage 8 dropped it; see docs/STAGE-8-TV-STYLE-OVERLAY.md
# "What's explicitly deferred" and the bug report that flagged it as a
# real regression, not just an unbuilt placeholder).
IMMERSIVE_MODE_FILE = (
    Path(os.environ.get("XDG_DATA_HOME", str(Path.home() / ".local" / "share")))
    / "ARKtube"
    / ".storage"
    / "immersiveMode.neustorage"
)


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
            print(
                "overlay.py: window.native was unset in before_show; "
                "cannot initialize gtk-layer-shell. This window will "
                "fall back to unmanaged placement, which is the Stage 8 "
                "bug this file exists to fix."
            )
            return
        try:
            _init_layer_shell(gtk_window, config)
        except Exception as exc:  # noqa: BLE001 - fail loud, not silent; see above
            print(
                "overlay.py: gtk-layer-shell init failed "
                f"({exc!r}). Is gir1.2-gtklayershell-0.1 / "
                "libgtk-layer-shell0 installed? Falling back to "
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


class SystemAPI:
    """
    JS-callable bridge exposed to static/app.js as `pywebview.api.*`.
    """

    def __init__(self):
        self.window = None
        self.width = 1920  # overwritten in main() from the real screen
        self.locked = False
        self._stop_watcher = threading.Event()

    # ---- panel state ------------------------------------------------------

    def set_panel(self, panel):
        """Resize the window to fit the requested panel ('none' or
        'overlay'). Ignored while locked -- the lock screen owns the
        window's size/anchors until unlock() runs."""
        if self.locked:
            return "locked"
        height = PANEL_HEIGHT if panel == "overlay" else BAR_HEIGHT
        if self.window is not None:
            self.window.resize(self.width, height)
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

        Post-cutover, the second problem no longer applies: cage is the
        whole session (there is no separate gnome-session layer to
        bypass), and cage's own sigchld handling means it exits the
        moment its direct child process exits -- see cage.c's
        sigchld_handler() and session/cage/arktube-cage-session, which
        is that direct child. So `loginctl terminate-session` against
        *our own* session is now genuinely the right primitive, not a
        bypass of a better one, because there is no better one in this
        architecture.

        The first problem is fixed properly here: prefer
        $XDG_SESSION_ID, which systemd-logind's pam_systemd already
        exports into every login session's environment (so it doesn't
        depend on `show-session self`'s systemd-233+ "self" alias
        working); fall back to the `show-session self` query only if
        that's unset; and if *both* are unavailable, fall back to
        closing our own window rather than ever calling
        terminate-session/terminate-user with an empty argument -- with
        no other windows/output left, cage's own "no views left, and
        the primary was the one that mattered" exit path
        (view_destroy() in the cage fork) ends the session anyway, just
        one step later than a direct terminate-session call would.
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

    # ---- immersive-mode auto-hide -------------------------------------------
    # Restores the behavior docs/STAGE-7-VISIBILITY-AND-CURSOR.md built
    # and docs/STAGE-8-TV-STYLE-OVERLAY.md's rewrite dropped (flagged
    # there under "What's explicitly deferred," but still a real
    # regression versus Stage 6/7 -- see the bug report's item 5).

    def _immersive_mode_enabled(self):
        try:
            return IMMERSIVE_MODE_FILE.read_text().strip() == "1"
        except OSError:
            return False

    def start_visibility_watcher(self):
        def watch():
            last_should_show = None
            while not self._stop_watcher.is_set():
                if not self.locked:
                    should_show = (not self._immersive_mode_enabled()) or (
                        not self._connectivity_full()
                    )
                    if should_show != last_should_show:
                        last_should_show = should_show
                        if self.window is not None:
                            self.window.evaluate_js(
                                "window.__cgSetImmersiveHidden && "
                                f"window.__cgSetImmersiveHidden({str(not should_show).lower()})"
                            )
                            if not should_show:
                                # Force the panel closed before hiding,
                                # so it can't be stuck open-but-invisible
                                # the next time Immersive Mode drops --
                                # matches Stage 7's own topbar.py
                                # behavior.
                                self.set_panel("none")
                time.sleep(STATUS_POLL_SECONDS)

        threading.Thread(target=watch, daemon=True).start()

    def stop_visibility_watcher(self):
        self._stop_watcher.set()


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
        resizable=False,
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
    window.events.shown += lambda: api.start_visibility_watcher()
    webview.start(gui="gtk")


if __name__ == "__main__":
    main()
