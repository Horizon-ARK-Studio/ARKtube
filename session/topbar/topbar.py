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

Stage 7 (see docs/STAGE-7-VISIBILITY-AND-CURSOR.md) adds the visibility
watcher at the bottom of this file: the bar auto-hides while ARKtube's
own Immersive Mode is on and the machine is online, and auto-reveals
itself the moment either of those stops being true -- Immersive Mode is
turned off, or the network drops -- so the controls this file exists to
provide are never actually unreachable.
"""

import os
import subprocess
import threading
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

# How often the Stage 7 visibility watcher re-checks Immersive Mode and
# connectivity. Both checks are cheap local reads (a small file, a cached
# NetworkManager state -- see _internet_available()), so this can run
# often enough that "exit Immersive Mode" feels immediate without ever
# making a network round-trip itself.
VISIBILITY_POLL_SECONDS = 1


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


def _arktube_data_dir():
    """Same rule main's build-deb.sh launcher and AppRun already use:
    ``${XDG_DATA_HOME:-$HOME/.local/share}/ARKtube``. Not configurable
    from here -- this only ever *reads* the directory main's own
    launcher decides on, never writes to it."""
    data_home = os.environ.get("XDG_DATA_HOME") or str(Path.home() / ".local" / "share")
    return Path(data_home) / "ARKtube"


def _immersive_mode_enabled():
    """Read main's persisted Immersive Mode flag directly off disk.

    This is the exact file app-init.js's Immersive Mode button writes via
    Neutralino.storage, and the exact file main's own build-deb.sh
    launcher and AppRun already read to decide --chrome-args for the next
    launch (see docs/bugs-caught/IMMERSIVE-MODE.md on the `main` branch).
    Reading it here doesn't add a second source of truth -- it's the same
    plain ``<value>`` file ("1" or "0"), read the same way, for a third
    purpose: deciding whether this bar should currently be visible.

    Missing or unreadable (Immersive Mode never toggled, main not
    installed, ARKtube not yet launched) fails open to "not immersive",
    the same direction app-init.js's own loadImmersiveModePreference()
    already fails in -- an unreadable preference must never assume the
    stricter state.
    """
    flag_file = _arktube_data_dir() / ".storage" / "immersiveMode.neustorage"
    try:
        return flag_file.read_text().strip() == "1"
    except OSError:
        return False


def _internet_available():
    """True only when NetworkManager reports full connectivity.

    Deliberately the plain ``nmcli networking connectivity`` read, not
    ``... connectivity check`` -- the latter forces a fresh probe and can
    block for a few seconds, which this function can't afford to do since
    it's polled once a second. NetworkManager already runs its own
    periodic connectivity checks in the background and caches the result;
    this just reads that cache, the same way _wifi() above reads nmcli's
    already-cached device state rather than triggering new work itself.

    Anything other than a confirmed "full" -- "limited", "portal", "none",
    "unknown", or nmcli missing entirely -- is treated as *not* available.
    This fails open toward showing the bar: a bar that's visible on a
    connection that turns out to be fine is a minor cosmetic annoyance; a
    bar that's hidden because a shaky "probably fine" connection was
    trusted strands the user exactly when they'd need Wi-Fi controls
    most.
    """
    out = run(["nmcli", "networking", "connectivity"])
    return out is not None and out.strip().lower() == "full"


def _visibility_watcher(api):
    """Background loop: hide the bar when (and only when) Immersive Mode
    is on *and* the machine is online; show it the moment either stops
    being true. Runs for the lifetime of the process, on its own thread,
    so the GTK/WebKit main loop `webview.start()` owns is never blocked
    waiting on a file read or an nmcli call.

    Calling window.show()/hide()/resize() from a non-main thread is
    already how this file works today -- set_panel() above does the same
    thing from the JS-bridge thread pywebview calls it on -- so this
    doesn't introduce a new pattern, just a second caller of it.
    """
    visible = True
    while True:
        try:
            should_show = (not _immersive_mode_enabled()) or (not _internet_available())
        except Exception:
            # Whatever went wrong, fail toward showing the bar -- see
            # _internet_available()'s own reasoning for why "wrongly
            # visible" is always the safer wrong answer here.
            should_show = True

        if should_show != visible and api.window is not None:
            if should_show:
                api.window.show()
            else:
                # Collapse any open panel and shrink back to bar-only
                # height first, so the window never re-appears later
                # frozen mid-panel from before it was hidden.
                api.set_panel("none")
                try:
                    api.window.evaluate_js(
                        "window.__arktubeTopbarCollapse && window.__arktubeTopbarCollapse();"
                    )
                except Exception:
                    # The page may not have finished loading yet on the
                    # very first pass; the window is still correctly
                    # collapsed to bar height by set_panel() above either
                    # way, this only resets the JS-side panel classes.
                    pass
                api.window.hide()
            visible = should_show

        time.sleep(VISIBILITY_POLL_SECONDS)


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

    # Stage 7: start the visibility watcher before the blocking
    # webview.start() call below hands this thread over to the GTK main
    # loop. daemon=True so it never needs its own shutdown handling --
    # it exits the same way every other thread in this process does,
    # when the process itself is reaped (see docs/STAGE-6-OFFLINE-TOPBAR.md,
    # "Lifecycle", for how that reaping already happens).
    threading.Thread(target=_visibility_watcher, args=(api,), daemon=True).start()

    webview.start()


if __name__ == "__main__":
    main()
