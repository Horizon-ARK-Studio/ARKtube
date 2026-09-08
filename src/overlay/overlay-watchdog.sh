#!/usr/bin/env bash
# overlay-watchdog.sh — keeps overlay.js's process *slot* alive under
# systemd-inhibit, independently of whether overlay.js itself keeps
# crashing.
#
# THE BUG THIS FIXES: 20-arktube.conf used to run
#
#     systemd-inhibit --what=handle-power-key:idle --mode=block \
#         gjs -m overlay.js
#
# systemd-inhibit(1) is explicit about its own contract: "The lock will
# be acquired before the specified command line is executed and
# released afterwards" -- i.e. the instant the wrapped command's
# process exits, for ANY reason, the handle-power-key inhibitor drops.
# overlay.js is a GTK3 + WebKit2 GJS process with a real dependency
# chain (gir1.2-webkit2-4.1/libwebkit2gtk-4.1-0, gir1.2-gtklayershell-0.1)
# that can be missing or mismatched on a given image; when any of its
# guarded imports throw (see overlay.js's own try/catch-and-log around
# those), the process exits immediately, systemd-inhibit exits right
# behind it, and the inhibitor lock is gone -- silently, with nothing
# on screen to show for it. The very next physical Power press then
# reaches systemd-logind's own default HandlePowerKey=poweroff
# unopposed and shuts the machine down with no menu, no confirmation,
# no second chance. That's "works the first press or two, then one day
# just powers off instantly" -- it depends entirely on overlay.js's own
# process staying alive for as long as the session runs, which nothing
# was actually guaranteeing.
#
# THE FIX: put something between systemd-inhibit and overlay.js that
# systemd-inhibit wraps instead, and that itself never exits -- this
# script. `exec systemd-inhibit ... overlay-watchdog.sh` (see
# 20-arktube.conf) now holds the inhibitor lock for as long as *this*
# loop runs, and this loop only exits if `sway` itself is going down
# (session end), not because the GJS process it supervises happened to
# crash. overlay.js crashing is now just something this script retries,
# with a real record of it in overlay.log, instead of something that
# silently disarms the physical Power key.
#
# This is also why XF86PowerOff itself (see 20-arktube.conf) was
# already routed straight to the standalone `power-menu` C binary and
# not through overlay.js/this script at all: power-menu has no GJS,
# WebKit2, or overlay.js dependency of any kind, so even a
# permanently-broken overlay.js (missing typelib, bad GJS syntax,
# whatever) no longer takes the power menu down with it -- as long as
# *something* is holding the inhibitor lock long enough for Sway's own
# bindsym to get the keypress, which is exactly what this script's job
# now is.
set -uo pipefail
# Deliberately no `-e`: this script's whole purpose is to keep running
# after its supervised command fails, so a non-zero exit from gjs must
# not itself end the loop.

DIR="$HOME/.local/share/arktube-overlay"
OVERLAY_JS="$DIR/overlay.js"
LOG="$DIR/overlay.log"
PIDFILE="$DIR/overlay.pid"

# A tight crash loop (overlay.js dying in well under a second, e.g. an
# import that fails immediately) would otherwise spin this script at
# effectively 100% of a core and spam overlay.log continuously. One
# second is enough to keep restarts responsive (a real, fixable crash
# gets retried well within the time it'd take anyone to notice) while
# capping the worst case to one gjs process start per second.
BACKOFF_SECONDS=1

log() {
    printf '%s WARNING overlay-watchdog: %s\n' "$(date -Iseconds)" "$1" >>"$LOG" 2>/dev/null
}

log "starting (supervising ${OVERLAY_JS})"

while true; do
    gjs -m "$OVERLAY_JS"
    status=$?

    # overlay.js writes/removes $PIDFILE itself (see its writePidFile()/
    # removePidFile()), but removePidFile() only ever runs from that
    # script's own `finally` block -- a hard kill (OOM, SIGKILL, a crash
    # inside a GLib callback that GJS can't unwind through) skips it and
    # leaves a stale PID on disk pointing at a process that's already
    # gone. 20-arktube.conf's $mod+m/Menu bindsyms would then either
    # silently signal nothing, or -- worse, once that PID number gets
    # reused by some unrelated later process -- signal whatever that is
    # instead. Belt-and-suspenders cleanup here, after every exit
    # regardless of how it happened: if the PID on file is not a live
    # process, remove it, so the next bindsym press finds a clean
    # "nothing to signal" state rather than a stale one.
    if [ -f "$PIDFILE" ]; then
        stale_pid="$(cat "$PIDFILE" 2>/dev/null)"
        if [ -n "$stale_pid" ] && ! kill -0 "$stale_pid" 2>/dev/null; then
            rm -f "$PIDFILE"
            log "removed stale overlay.pid (pid ${stale_pid} is not running) after overlay.js exit"
        fi
    fi

    log "overlay.js exited (code ${status}) -- restarting in ${BACKOFF_SECONDS}s. The handle-power-key inhibitor stays held throughout this restart (that's this script's whole job) -- see this file's own header comment."
    sleep "$BACKOFF_SECONDS"
done
