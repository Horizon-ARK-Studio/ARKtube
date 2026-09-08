#!/usr/bin/env bash
# osd-notify.sh volume|brightness
#
# Called by 20-arktube.conf's Volume/Brightness bindsyms right after they
# run wpctl/brightnessctl (see that file). Computes the fresh level,
# drops it in osd.state, and either signals an already-running osd
# process to pick it up (resetting its own hide timer) or launches a new
# one -- same singleton-via-PID-file pattern as overlay.py's own
# Menu/$mod+s/Power signaling fix, and for the same reason: a held key
# firing several times a second must reuse one window, not spawn a new
# GTK process per repeat.
set -euo pipefail

DIR="$HOME/.local/share/arktube-overlay"
STATE="$DIR/osd.state"
PIDFILE="$DIR/osd.pid"
BIN="$DIR/osd"

kind="${1:-}"
if [ "$kind" != "volume" ] && [ "$kind" != "brightness" ]; then
    echo "usage: osd-notify.sh volume|brightness" >&2
    exit 1
fi

muted=0
if [ "$kind" = "volume" ]; then
    # Same wpctl-first, pactl-fallback pattern as overlay.py's own
    # _volume() -- see that function's own comment for why.
    out="$(wpctl get-volume @DEFAULT_AUDIO_SINK@ 2>/dev/null || true)"
    if [ -n "$out" ]; then
        level="$(awk '{printf "%d", ($2*100)+0.5}' <<<"$out")"
        [[ "$out" == *MUTED* ]] && muted=1
    else
        out="$(pactl get-sink-volume @DEFAULT_SINK@ 2>/dev/null || true)"
        level="$(grep -oP '\d+(?=%)' <<<"$out" | head -1)"
        level="${level:-0}"
        [[ "$(pactl get-sink-mute @DEFAULT_SINK@ 2>/dev/null || true)" == *yes* ]] && muted=1
    fi
else
    cur="$(brightnessctl get 2>/dev/null || echo 0)"
    max="$(brightnessctl max 2>/dev/null || echo 0)"
    if [ "${max:-0}" -gt 0 ]; then
        level=$(( (cur * 100 + max / 2) / max ))
    else
        level=0
    fi
fi

level="${level:-0}"
mkdir -p "$DIR"
echo "$kind $level $muted" > "$STATE"

if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; then
    kill -SIGUSR1 "$(cat "$PIDFILE")" 2>/dev/null || true
else
    setsid "$BIN" >/dev/null 2>&1 < /dev/null &
    disown
fi
