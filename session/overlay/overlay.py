#!/usr/bin/env python3
"""
overlay.py — Stage 8: TV-style system overlay for the ARKtube Webtop
session.

This replaces Stage 6/7's GNOME Shell-styled topbar (session/topbar,
now removed — see docs/STAGE-8-TV-STYLE-OVERLAY.md). The controls this
file exposes are the same class of thing the root README's "session
controls" bullet already promised (volume, brightness, network, lock,
logout, power) — what changed is the reference point: a GNOME Shell
quick-settings popover is a desktop idiom, and ARKtube is a TV
appliance navigated with a remote/controller, not a mouse and a top
panel. This file borrows the *style and intent* of Google/Android TV's
own settings overlay (a small always-present status affordance that
expands into a row of large, remote-navigable tiles) without copying
it 1:1 — see docs/foundational/CAGE-MIGRATION.md for the fuller
rationale, including why the previous GNOME-shaped design was a
mistake for this product.

Same non-negotiable as Stage 6: every control here resolves locally
through NetworkManager (nmcli), PipeWire/WirePlumber (wpctl) with a
PulseAudio (pactl) fallback, upower, brightnessctl, and loginctl/systemd.
Nothing in this file makes a network request or depends on one
succeeding — a system settings surface that stops working the moment
the network it's meant to help you fix goes down would be its own kind
of bug.

Staging note (see docs/STAGE-8-TV-STYLE-OVERLAY.md): only the Network
(Wi-Fi/Ethernet) tile, and the always-visible brightness/volume
sliders, are real controls in this stage. The Picture, Sound, and
Bluetooth tiles are wired up in the UI and reachable by remote/keyboard
navigation, but their content panes are deliberately a "coming soon"
placeholder — see PLACEHOLDER_TILES below — until a later stage gives
each one an actual backend.
"""

import subprocess
import time
from pathlib import Path

import webview

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

# Window heights for each panel state. Same "single fixed-width,
# frameless strip that grows/shrinks downward" window model Stage 6
# established (see docs/STAGE-6-OFFLINE-TOPBAR.md, "Window model") —
# still the right call here: it lets the panel intercept input while
# open and get out of the way (both visually and for input) while
# closed, without a second window or a compositor-level layer-shell
# integration this branch doesn't have yet.
BAR_HEIGHT = 56
PANEL_HEIGHT = 620

# Tiles that exist in the UI this stage, but have no real backend yet.
# Keep this list, rather than hardcoding "coming soon" three separate
# times, so adding Bluetooth/Sound/Picture's real implementation later
# is a one-line removal here rather than a UI change too.
PLACEHOLDER_TILES = {"bluetooth", "sound", "picture"}

STATUS_POLL_SECONDS = 2


def run(cmd, timeout=3):
    """Run a local command, return stripped stdout, or None on any failure.

    Every caller here treats None as "control unavailable" and degrades
    the UI rather than raising — a missing tool (no brightnessctl on a
    machine with no backlight, no upower on a machine with no battery)
    is an expected case, not an error. Same contract Stage 6's topbar.py
    used.
    """
    try:
        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=timeout, check=False
        )
        return result.stdout.strip() if result.returncode == 0 else None
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return None


class SystemAPI:
    """
    JS-callable bridge exposed to static/app.js as `pywebview.api.*`.
    """

    def __init__(self):
        self.window = None
        self.width = 1920  # overwritten in main() from the real screen

    # ---- panel state ------------------------------------------------------

    def set_panel(self, panel):
        """Resize the window to fit the requested panel ('none' or
        'overlay'). The frontend still owns which content pane is
        visible inside the overlay; this only grows/shrinks the window
        rectangle to match, so ARKtube underneath is never obscured
        while the overlay is collapsed to its corner status pill."""
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
        # Plain cached read, not `... connectivity check` — see Stage 6's
        # _internet_available() reasoning: this must never block waiting
        # on a fresh probe, since it backs a status poll on a timer.
        out = run(["nmcli", "networking", "connectivity"])
        return out is not None and out.strip().lower() == "full"

    def _network(self):
        """Ethernet-priority network status, per the product spec:
        prefer Ethernet whenever it's connected and stable; fall back to
        Wi-Fi the moment Ethernet is absent, disconnected, or up but not
        actually passing traffic (nmcli's connectivity state below
        'full')."""
        devices = self._devices()
        ethernet_connected = any(
            d["type"] == "ethernet" and d["state"] == "connected" for d in devices
        )
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
            # Ethernet link is up but not actually passing traffic --
            # treat it as unstable and let Wi-Fi cover if it can, same
            # as the product spec's "unstable ... switches over to
            # Wi-Fi" requirement.
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
        # Same rule as Stage 6: enumerate rather than hardcode BAT0, and
        # return None (not a fake 0%) when there's no battery at all --
        # that's exactly the "desktop vs laptop" signal the frontend
        # uses to decide whether to render the battery pill at all.
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
        """List visible Wi-Fi access points, strongest signal per SSID,
        sorted best-first. Returns [] rather than None on failure so the
        frontend can render an empty-state instead of an error state --
        "no networks in range" and "no wifi hardware" look the same to
        a viewer and don't need different UI."""
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
        # If Wi-Fi hardware isn't reporting at all, `available` is
        # already False and there is nothing to toggle -- nmcli would
        # just fail silently, so don't bother calling it.
        turning_on = not wifi.get("available") or not self._network().get("wifi_radio_on", True)
        run(["nmcli", "radio", "wifi", "on" if turning_on else "off"])
        return self._network()

    def network_connect(self, ssid, password=""):
        """Connect to an SSID, optionally with a password. Returns
        {"success": bool, "network": <fresh status>} rather than raising
        -- a wrong password or an out-of-range AP is an expected, common
        outcome here, not an exceptional one, and the overlay should be
        able to show "couldn't connect" without a stack trace."""
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

    # ---- session lifecycle (unchanged surface from Stage 6) ---------------
    # Kept here, not on any of the four tiles, because the product spec
    # for this stage only names Picture/Sound/Bluetooth/Network as
    # tiles. Lock/logout/power are the root README's own "session
    # controls" commitment (allow the session to be locked, allow the
    # user to log out) predating this redesign, so this stage keeps
    # them reachable from the overlay's small corner affordance instead
    # of dropping them while the four tiles above get rebuilt.

    def lock(self):
        run(["loginctl", "lock-session"])

    def logout(self):
        session_id = run(["loginctl", "show-session", "self", "-p", "Id", "--value"])
        if session_id:
            run(["loginctl", "terminate-session", session_id])
        else:
            run(["loginctl", "terminate-user", ""])

    def poweroff(self):
        run(["systemctl", "poweroff"])

    def reboot(self):
        run(["systemctl", "reboot"])


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
        x=0,
        y=0,
        frameless=True,
        on_top=True,
        transparent=True,
        resizable=False,
        easy_drag=False,
    )
    api.window = window
    webview.start()


if __name__ == "__main__":
    main()
