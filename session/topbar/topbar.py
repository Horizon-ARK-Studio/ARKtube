#!/usr/bin/env python3
"""
topbar.py — Stage 6: offline-first system topbar for the ARKtube Webtop
session.

GNOME Kiosk ships no panel, dock, or status bar by design (see
docs/foundational/PROBLEM-STATEMENT.md). That's correct for keeping
ARKtube the session, but it also means there is currently no way to
reach basic system controls -- volume, brightness, Wi-Fi, lock, logout,
power -- from inside the ARKtube session at all, with or without a
network connection.

This is a small always-on-top pywebview strip, modeled on GNOME Shell's
own top bar (clock + status area) and its quick-settings / calendar
popovers, that talks to the system directly through local CLI tools and
logind/systemd -- never over the network -- so every control here works
identically with Wi-Fi off, Airplane Mode on, or no network hardware
present at all. See docs/STAGE-6-OFFLINE-TOPBAR.md for what backs each
control and what happens when a given tool isn't installed.
"""

import subprocess
import time
from pathlib import Path

import webview

HERE = Path(__file__).resolve().parent
STATIC = HERE / "static"

# Window heights for each panel state. The window is a single fixed-width,
# frameless strip pinned to (0, 0); it grows downward when a panel opens
# and shrinks back to bar-only height when it closes. See
# docs/STAGE-6-OFFLINE-TOPBAR.md ("Window model") for why this, rather
# than a transparent full-screen overlay, is what's actually implemented.
BAR_HEIGHT = 32
PANEL_HEIGHTS = {"none": BAR_HEIGHT, "quicksettings": 460, "calendar": 720}


def run(cmd, timeout=3):
    """Run a local command, return stripped stdout, or None on any failure.

    Every caller in this file treats None as "control unavailable" and
    degrades the UI rather than raising -- a tool being missing (no
    brightnessctl on a desktop with no backlight, no upower on a machine
    with no batturey) is an expected case here, not an error.
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

    Every method here resolves locally: NetworkManager (nmcli),
    PipeWire/WirePlumber (wpctl) with a PulseAudio (pactl) fallback,
    upower, brightnessctl, gsettings, and loginctl/systemd. Nothing in
    this class makes a network request or depends on one succeeding.
    """

    def __init__(self):
        self.window = None
        self.width = 1920  # overwritten in main() from the real screen

    # ---- panel state ----------------------------------------------------

    def set_panel(self, panel):
        """Resize the window to fit the requested panel ('none',
        'quicksettings', or 'calendar'). The frontend still owns which
        panel's HTML is visible; this only grows/shrinks the window
        rectangle to match, so nothing below it is obscured while
        collapsed."""
        height = PANEL_HEIGHTS.get(panel, BAR_HEIGHT)
        if self.window is not None:
            self.window.resize(self.width, height)
        return panel

    # ---- status polling ---------------------------------------------------

    def get_status(self):
        return {
            "time": time.strftime("%H:%M"),
            "date": time.strftime("%A, %B ") + str(int(time.strftime("%d"))) + time.strftime(" %Y"),
            "battery": self._battery(),
            "volume": self._volume(),
            "brightness": self._brightness(),
            "wifi": self._wifi(),
            "airplane_mode": self._airplane_mode(),
            "dark_style": self._dark_style(),
            "night_light": self._night_light(),
        }

    def _battery(self):
        # upower's device path is stable on most laptops but not
        # guaranteed; enumerate rather than hardcode BAT0.
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

    def _airplane_mode(self):
        return run(["nmcli", "radio", "wifi"]) == "disabled"

    def _dark_style(self):
        out = run(["gsettings", "get", "org.gnome.desktop.interface", "color-scheme"])
        return out == "'prefer-dark'"

    def _night_light(self):
        out = run(
            ["gsettings", "get", "org.gnome.settings-daemon.plugins.color", "night-light-enabled"]
        )
        return out == "true"

    # ---- actions ----------------------------------------------------------

    def set_volume(self, level):
        level = max(0, min(100, int(level)))
        if run(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", f"{level}%"]) is None:
            run(["pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{level}%"])
        return self._volume()

    def set_brightness(self, level):
        level = max(1, min(100, int(level)))
        run(["brightnessctl", "set", f"{level}%"])
        return self._brightness()

    def toggle_mute(self):
        if run(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"]) is None:
            run(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"])
        return self._volume()

    def toggle_wifi(self):
        state = self._wifi()
        run(["nmcli", "radio", "wifi", "off" if state.get("connected") else "on"])
        return self._wifi()

    def toggle_airplane_mode(self):
        enabling = not self._airplane_mode()
        radio_state = "off" if enabling else "on"
        run(["nmcli", "radio", "wifi", radio_state])
        run(["nmcli", "radio", "wwan", radio_state])
        return self._airplane_mode()

    def toggle_dark_style(self):
        want = "'default'" if self._dark_style() else "'prefer-dark'"
        run(["gsettings", "set", "org.gnome.desktop.interface", "color-scheme", want])
        return self._dark_style()

    def toggle_night_light(self):
        want = "false" if self._night_light() else "true"
        run(
            ["gsettings", "set", "org.gnome.settings-daemon.plugins.color",
             "night-light-enabled", want]
        )
        return self._night_light()

    def lock(self):
        # loginctl talks to logind directly. Same "real session op, not
        # faked in application JS" principle the root README's design
        # principles already lay out for lock/logout -- this just gives
        # the kiosk session a button to reach it from.
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

    def screenshot(self):
        out_dir = Path.home() / "Pictures"
        out_dir.mkdir(parents=True, exist_ok=True)
        target = out_dir / f"Screenshot_{time.strftime('%Y-%m-%d_%H-%M-%S')}.png"
        if run(["gnome-screenshot", "-f", str(target)]) is None:
            run(["grim", str(target)])
        return str(target) if target.exists() else None


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
        "ARKtube Topbar",
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
