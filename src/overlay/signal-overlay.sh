#!/usr/bin/env bash
# signal-overlay.sh — validated replacement for the old
#
#     kill -SIGUSR1 "$(cat overlay.pid 2>/dev/null)" 2>/dev/null
#
# line that used to live directly in 20-arktube.conf's Menu/$mod+m
# bindsyms.
#
# THE BUG THIS FIXES: that inline form trusted overlay.pid completely.
# overlay.js can be crash-looping under overlay-watchdog.sh (missing
# gir1.2-webkit2-4.1/gir1.2-gtklayershell-0.1, GtkLayerShell.
# init_for_window() failing because the compositor doesn't support
# wlr-layer-shell-v1, etc. -- see overlay.js's own header comment and
# docs/bugs_caught/) with a stale PID left on disk from the last time it
# got far enough to write one, or the file can be simply missing. Either
# way, `kill -SIGUSR1 <stale-or-empty>` fails, and because both the
# `cat` and the `kill` were `2>/dev/null`, that failure was completely
# invisible -- $mod+m just silently "didn't work", with no way to tell
# that from the panel actually opening and closing too fast to notice.
# Worse: on a long enough uptime, that stale PID number can eventually
# get reused by a totally unrelated process, and the old inline command
# would happily deliver SIGUSR1 to it instead of failing at all.
#
# THE FIX: check that the PID on file both exists AND is actually a gjs
# process before signaling it, and log a diagnostic line either way --
# so a bad target now shows up in overlay.log as an actual clue instead
# of nothing happening at all. This also means overlay-watchdog.sh's own
# stale-PID cleanup (see that file) is defense in depth, not the only
# thing standing between a bad PID and a wrongly-signaled process.
set -uo pipefail

DIR="$HOME/.local/share/arktube-overlay"
PIDFILE="$DIR/overlay.pid"
LOG="$DIR/overlay.log"

log() {
    printf '%s WARNING overlay-signal: %s\n' "$(date -Iseconds)" "$1" >>"$LOG" 2>/dev/null
}

usage() {
    echo "usage: $0 SIGUSR1|SIGUSR2" >&2
    exit 2
}

[ $# -eq 1 ] || usage
signal="$1"
case "$signal" in
    SIGUSR1|SIGUSR2) ;;
    *) usage ;;
esac

pid="$(cat "$PIDFILE" 2>/dev/null)"
if [ -z "$pid" ]; then
    log "no PID in ${PIDFILE} (missing or empty) -- overlay.js is likely not running or crash-looping; ${signal} not sent. Check overlay.log above this line for why it isn't staying up."
    exit 1
fi

if ! kill -0 "$pid" 2>/dev/null; then
    log "PID ${pid} from ${PIDFILE} is not running (stale) -- ${signal} not sent."
    exit 1
fi

# Confirm the live PID is actually a gjs process, not some unrelated
# process that has since reused this PID number. /proc/<pid>/comm is
# the truncated (15-char) executable name; gjs's own binary name fits
# well within that, so an exact match is fine here.
comm="$(cat "/proc/$pid/comm" 2>/dev/null || true)"
if [ "$comm" != "gjs" ]; then
    log "PID ${pid} from ${PIDFILE} is running but is not gjs (comm='${comm}') -- looks like a reused/stale PID, not overlay.js. ${signal} not sent."
    exit 1
fi

kill "-${signal#SIG}" "$pid" 2>/dev/null
exit 0
