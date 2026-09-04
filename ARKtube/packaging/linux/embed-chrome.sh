#!/usr/bin/env bash
#
# embed-chrome.sh - hybrid window/chrome mode for ARKtube (X11 only).
#
# Neutralino now runs in plain "window" mode (see neutralino.config.json)
# and owns the real, top-level, window-manager-managed OS window. It loads
# nothing but a small local backdrop page (resources/index.html) -
# YouTube itself is rendered by a real, separately-spawned Chrome process,
# which this script reparents as an X11 *child* of Neutralino's window and
# keeps resized to fill it.
#
# The point of doing it this way instead of Neutralino's own built-in
# "chrome" mode: once Chrome's window is reparented into another
# application's window, the window manager no longer treats it as a
# managed top-level window at all - it has no taskbar entry, no WM-level
# fullscreen state, and (critically) global hotkeys grabbed at the WM/root
# level are dispatched before input ever reaches a focused child window,
# so they never reach Chrome. That's what lets F11/Escape/Home be handled
# against Neutralino's own window below, unconditionally, regardless of
# which window currently has input focus.
#
# Requires (X11 desktop only - this cannot work on Wayland, which does not
# allow one client to reparent another client's window; ARKtube falls
# back to plain window-mode-only there, see the XDG_SESSION_TYPE check
# below): xdotool, wmctrl, xbindkeys.
#
# Usage:
#   embed-chrome.sh <neutralino-window-title> <youtube-url> <chrome-profile-dir> [--immersive]
#
set -euo pipefail

WINDOW_TITLE="${1:?usage: embed-chrome.sh <window-title> <url> <profile-dir> [--immersive]}"
YOUTUBE_URL="${2:?usage: embed-chrome.sh <window-title> <url> <profile-dir> [--immersive]}"
CHROME_PROFILE_DIR="${3:?usage: embed-chrome.sh <window-title> <url> <profile-dir> [--immersive]}"
IMMERSIVE="${4:-}"

log() { echo "embed-chrome: $*" >&2; }

if [ "${XDG_SESSION_TYPE:-}" = "wayland" ]; then
    log "Wayland session detected - arbitrary window reparenting isn't" \
        "permitted here. Falling back to plain window mode; ARKtube will" \
        "keep running with its own webview loading the local shell page" \
        "only. Run under Xwayland/X11 for the hybrid embed."
    exit 0
fi

for tool in xdotool wmctrl xbindkeys; do
    if ! command -v "${tool}" >/dev/null 2>&1; then
        log "'${tool}' not found. Install xdotool, wmctrl and xbindkeys to" \
            "enable the hybrid embed (falling back to plain window mode)."
        exit 0
    fi
done

CHROME_BIN=""
for candidate in google-chrome-stable google-chrome chromium chromium-browser; do
    if command -v "${candidate}" >/dev/null 2>&1; then
        CHROME_BIN="${candidate}"
        break
    fi
done
if [ -z "${CHROME_BIN}" ]; then
    log "no Chrome/Chromium binary found on PATH - falling back to plain window mode."
    exit 0
fi

# --- 1. Wait for Neutralino's own top-level window -----------------------
log "waiting for Neutralino window '${WINDOW_TITLE}'..."
NEUTRALINO_WIN=""
for _ in $(seq 1 50); do
    NEUTRALINO_WIN="$(xdotool search --name "^${WINDOW_TITLE}\$" 2>/dev/null | head -n1 || true)"
    [ -n "${NEUTRALINO_WIN}" ] && break
    sleep 0.1
done
if [ -z "${NEUTRALINO_WIN}" ]; then
    log "never found the Neutralino window; giving up on the embed."
    exit 0
fi
log "found Neutralino window ${NEUTRALINO_WIN}"

# --- 2. Launch Chrome, borderless ("--app" mode), pointed at the same ---
#        geometry Neutralino's window currently has. Deliberately no
#        --start-fullscreen/--kiosk-only-fullscreen flag: fullscreen is
#        owned by Neutralino's window (see the xbindkeys grab below), not
#        by Chrome, so Chrome is only ever asked to be exactly the size
#        of the area it's being embedded into.
read -r PARENT_X PARENT_Y PARENT_W PARENT_H < <(
    xdotool getwindowgeometry --shell "${NEUTRALINO_WIN}" \
        | awk -F= '/^X=/{x=$2} /^Y=/{y=$2} /^WIDTH=/{w=$2} /^HEIGHT=/{h=$2} END{print x, y, w, h}'
)

CHROME_UA="Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible;"
CHROME_ARGS=(
    "--app=${YOUTUBE_URL}"
    "--user-data-dir=${CHROME_PROFILE_DIR}"
    "--user-agent=${CHROME_UA}"
    "--window-position=0,0"
    "--window-size=${PARENT_W:-1280},${PARENT_H:-720}"
    "--no-first-run"
    "--no-default-browser-check"
)
if [ "${IMMERSIVE}" = "--immersive" ]; then
    # Real, launch-time Chrome hardening - mirrors what AppRun/the .deb
    # launcher used to pass to Neutralino's own chrome mode. --disable-pinch
    # and overscroll-history-navigation stop accidental back/forward
    # gestures on a touch/trackpad remote from fighting youtube.com/tv's
    # own navigation.
    CHROME_ARGS+=(--disable-dev-tools --disable-pinch --overscroll-history-navigation=0)
fi

"${CHROME_BIN}" "${CHROME_ARGS[@]}" &
CHROME_PID=$!
log "launched ${CHROME_BIN} (pid ${CHROME_PID})"
echo "${CHROME_PID}" > "/tmp/.arktube-chrome.pid"

# --- 3. Wait for Chrome's own top-level window, then reparent it --------
CHROME_WIN=""
for _ in $(seq 1 100); do
    CHROME_WIN="$(xdotool search --pid "${CHROME_PID}" --onlyvisible --name . 2>/dev/null | head -n1 || true)"
    [ -n "${CHROME_WIN}" ] && break
    sleep 0.1
done
if [ -z "${CHROME_WIN}" ]; then
    log "Chrome never presented a window; leaving it unembedded."
    exit 0
fi
log "found Chrome window ${CHROME_WIN}, reparenting into ${NEUTRALINO_WIN}"

xdotool windowreparent "${CHROME_WIN}" "${NEUTRALINO_WIN}"
xdotool windowmove "${CHROME_WIN}" 0 0
xdotool windowsize "${CHROME_WIN}" "${PARENT_W:-1280}" "${PARENT_H:-720}"

# --- 4. Global hotkeys, grabbed at the root window -----------------------
# xbindkeys uses XGrabKey, which intercepts the keypress before the X
# server delivers it to whichever window currently has focus - so these
# never reach the embedded Chrome window at all, regardless of focus.
# Everything here acts on Neutralino's window (via wmctrl/xdotool), never
# on Chrome directly - that's the whole point of the hybrid approach.
XBINDKEYS_CONF="$(mktemp /tmp/arktube-xbindkeysrc.XXXXXX)"
cat > "${XBINDKEYS_CONF}" <<EOF
# F11: toggle Neutralino's own top-level fullscreen state via EWMH.
# This never asks Chrome to do anything - Chrome, now reparented, isn't
# even WM-managed anymore, so it has no fullscreen state of its own to
# fight this over.
"wmctrl -i -r ${NEUTRALINO_WIN} -b toggle,fullscreen"
    F11

# Escape: only ever backs out of fullscreen (never quits - matches the
# existing resources/js/app-init.js behavior for the pre-hybrid build).
"wmctrl -i -r ${NEUTRALINO_WIN} -b remove,fullscreen"
    Escape

# Home: youtube.com/tv is a hash-routed SPA; send it back to the root
# route the same way app-init.js's goHome() used to, but from outside the
# page this time, via xdotool sending the keystroke *to the Chrome window
# specifically* so its own JS can still see and act on it (unlike F11/Escape,
# this one Chrome is meant to receive, just always targeted precisely
# instead of relying on whichever window happens to have focus).
"xdotool key --window ${CHROME_WIN} Home"
    Home
EOF

xbindkeys -f "${XBINDKEYS_CONF}" &
XBINDKEYS_PID=$!
log "xbindkeys grabbing F11/Escape/Home globally (pid ${XBINDKEYS_PID})"

cleanup() {
    log "cleaning up (chrome pid ${CHROME_PID}, xbindkeys pid ${XBINDKEYS_PID})"
    kill -TERM "${XBINDKEYS_PID}" 2>/dev/null || true
    kill -TERM "${CHROME_PID}" 2>/dev/null || true
    rm -f "${XBINDKEYS_CONF}" "/tmp/.arktube-chrome.pid"
}
trap cleanup EXIT INT TERM

# --- 5. Geometry-follow loop ----------------------------------------------
# X11 reparenting does not make a child auto-resize when its new parent
# does - Neutralino resizing/maximizing/fullscreening its own window
# (including via the wmctrl call above) has no effect on the embedded
# Chrome window unless something explicitly tells it the new geometry.
# This polls Neutralino's window and keeps Chrome's child window matched
# to it. 150ms keeps a fullscreen toggle or manual resize feeling
# essentially immediate without busy-looping.
LAST_GEOM=""
while kill -0 "${CHROME_PID}" 2>/dev/null && xdotool getwindowname "${NEUTRALINO_WIN}" >/dev/null 2>&1; do
    GEOM="$(xdotool getwindowgeometry --shell "${NEUTRALINO_WIN}" 2>/dev/null || true)"
    if [ -n "${GEOM}" ] && [ "${GEOM}" != "${LAST_GEOM}" ]; then
        LAST_GEOM="${GEOM}"
        read -r W H < <(echo "${GEOM}" | awk -F= '/^WIDTH=/{w=$2} /^HEIGHT=/{h=$2} END{print w, h}')
        xdotool windowmove "${CHROME_WIN}" 0 0 2>/dev/null || true
        xdotool windowsize "${CHROME_WIN}" "${W}" "${H}" 2>/dev/null || true
    fi
    sleep 0.15
done

log "Neutralino window or Chrome process gone; exiting."
