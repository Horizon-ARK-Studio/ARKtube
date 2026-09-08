#!/usr/bin/env bash
#
# install.sh — makes ARKtube selectable from Ubuntu/GDM's gear icon,
# running under Sway instead of GNOME Kiosk. See docs/foundational/
# PROBLEM_STATEMENT.md for why Sway, and docs/foundational/SYSTEM_DESIGN.md
# for what each layer below is responsible for.
#
# Deliberately everything here is a packaged binary + a dropped-in file:
# no compositor forked or compiled (that's exactly what this branch
# stopped doing — see PROBLEM_STATEMENT.md's "What was tried" section),
# and no wrapper script between GDM and sway either — GDM's Exec=sway is
# the same binary the `sway` package's own session entry already uses.
# ARKtube's behavior lives entirely in the /etc/sway/config.d/ fragments
# this script installs, not in a custom launcher.
#
# Requires: ARKtube already built/installed from `main` (so
# `arktube_linux` is on PATH — see main's arktube_linux/CMakeLists.txt).
# This script does not build or install ARKtube itself; see the root
# README's "What this is not" section for why that boundary matters.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SESSION="${HERE}/src/session"
OVERLAY="${HERE}/src/overlay"

if ! command -v arktube_linux >/dev/null 2>&1; then
    echo "warning: 'arktube_linux' is not on PATH yet." >&2
    echo "         Build and install ARKtube from main first, then re-run this script." >&2
fi

echo "==> Installing Sway and the overlay's runtime dependencies"
sudo apt-get update
sudo apt-get install -y \
    sway \
    gjs gir1.2-webkit2-4.1 gir1.2-gtklayershell-0.1 \
    network-manager wireplumber pulseaudio-utils brightnessctl upower \
    build-essential pkg-config libgtk-3-dev libgtk-layer-shell-dev
# python3-pip/python3-gi are gone from this list: the overlay itself
# (overlay.js) is now GJS, not Python -- see src/overlay/overlay.js's own
# header for why. `gjs` replaces them, and pulls in the same GTK3
# typelib GObject-introspection needs anyway. gir1.2-webkit2-4.1 and
# gir1.2-gtklayershell-0.1 are unchanged: overlay.js uses the exact same
# WebKit2/gtk-layer-shell libraries overlay.py did, just from GJS instead
# of PyGObject.
# The build-essential/pkg-config/libgtk-3-dev/libgtk-layer-shell-dev line
# is unchanged: build-time-only deps for src/overlay/osd/osd.c and
# src/overlay/power-menu/power-menu.c, neither of which changed here.
# The resulting binaries link against libgtk-3-0/libgtk-layer-shell0
# (already pulled in transitively above) and need none of these -dev
# packages at runtime.
# No seatd here: Ubuntu ships systemd-logind, and Sway uses logind as its
# seat backend automatically when one is present — seatd is only needed
# on non-systemd or non-logind setups, neither of which is Ubuntu/GDM.
# This resolves the root README's "seatd vs. logind" open item, for this
# distro at least.

echo "==> Adding a gear-menu entry named ARKtube"
sudo install -Dm644 "${SESSION}/wayland-sessions/arktube.desktop" \
    /usr/share/wayland-sessions/arktube.desktop
# The `sway` package ships its own /usr/share/wayland-sessions/sway.desktop
# (Name=Sway) alongside this one, same sibling-file approach webtop used
# for gnome-kiosk-script-session rather than editing a package-owned
# conffile. One real difference from webtop's version of this step: both
# entries launch the exact same `sway` binary with the exact same
# /etc/sway/config.d/*, so picking "Sway" from the gear menu boots into
# ARKtube fullscreen too, not a blank desktop. On a machine dedicated to
# this appliance that's arguably correct; if a genuine plain-Sway session
# is ever needed alongside this one, that needs a second Sway config
# path and a wrapper script pointing at it — deliberately not built here
# since nothing has asked for it yet.

echo "==> Installing ARKtube's Sway config"
sudo install -Dm644 "${SESSION}/sway/config.d/10-systemd.conf" \
    /etc/sway/config.d/10-systemd.conf
sudo install -Dm644 "${SESSION}/sway/config.d/20-arktube.conf" \
    /etc/sway/config.d/20-arktube.conf

# Ubuntu's stock /etc/sway/config already includes config.d/* by
# convention, but that's verified here rather than assumed — same
# "checked directly" approach this project has used at every prior
# stage — since a missing include line would mean everything just
# installed above silently never runs.
if [ -f /etc/sway/config ] && grep -qE '^\s*include\s+/etc/sway/config\.d/\*' /etc/sway/config; then
    echo "    /etc/sway/config already includes config.d/* — nothing to add"
else
    echo "    /etc/sway/config does not include config.d/* — appending it"
    echo 'include /etc/sway/config.d/*' | sudo tee -a /etc/sway/config >/dev/null
fi

echo "==> Installing the sway-session.target unit"
mkdir -p "${HOME}/.config/systemd/user"
install -Dm644 "${SESSION}/systemd/user/sway-session.target" \
    "${HOME}/.config/systemd/user/sway-session.target"
systemctl --user daemon-reload 2>/dev/null || true

# systemd-logind's own default (HandlePowerKey=poweroff, see logind.conf(5))
# grabs the physical power button directly at the seat level and runs its
# own immediate `systemctl poweroff` the instant the key event arrives —
# independently of, and faster than, anything a compositor's `bindsym` can
# do about it. GNOME/KDE never hit this because gnome-shell/kwin each take
# out logind's own "handle-power-key" inhibitor lock so they can show their
# own power dialog instead; Sway does neither, so without this, the
# XF86PowerOff bindsym in 20-arktube.conf never gets a chance to run at all
# — pressing Power just shuts the machine down, no overlay.
#
# Fixed the GNOME way, not the config-file way: 20-arktube.conf now execs
# overlay.py through `systemd-inhibit --what=handle-power-key --mode=block`,
# which takes the same logind inhibitor lock gnome-session/gnome-shell take
# on every GNOME session — no /etc/systemd file written, no systemd daemon
# restarted here. The lock is scoped to overlay.py's own process lifetime
# and releases itself on logout, so there's nothing for this script (or
# uninstall.sh) to install or clean up for it.

# brightnessctl (installed above) writes to /sys/class/backlight/*/brightness,
# which its own udev rules (installed by the brightnessctl package) only
# grant to the `video` group — without this, `brightnessctl set` fails
# with "Permission denied" for any non-root user, silently, since Sway's
# `exec` discards the command's stderr. Confirmed against brightnessctl's
# own upstream docs and udev rules. Membership takes effect on next login,
# same as any other group change — a fresh GDM login after this script
# finishes is enough, no reboot required.
echo "==> Adding $(whoami) to the video group, for brightnessctl"
sudo usermod -aG video "$(whoami)"

echo "==> Deploying the system overlay"
mkdir -p "${HOME}/.local/share/arktube-overlay/static"
install -Dm755 "${OVERLAY}/overlay.js" "${HOME}/.local/share/arktube-overlay/overlay.js"
install -Dm644 "${OVERLAY}/static/index.html" "${HOME}/.local/share/arktube-overlay/static/index.html"
install -Dm644 "${OVERLAY}/static/style.css" "${HOME}/.local/share/arktube-overlay/static/style.css"
install -Dm644 "${OVERLAY}/static/bridge.js" "${HOME}/.local/share/arktube-overlay/static/bridge.js"
install -Dm644 "${OVERLAY}/static/app.js" "${HOME}/.local/share/arktube-overlay/static/app.js"
# No `pip install` here any more -- overlay.js is GJS, not Python, so
# there are no pip-managed dependencies left to install (`gjs` itself
# came from apt, above).


echo "==> Building and deploying the standalone volume/brightness OSD"
gcc -O2 -Wall \
    $(pkg-config --cflags gtk-layer-shell-0 gtk+-3.0) \
    -o "${HERE}/src/overlay/osd/osd" \
    "${OVERLAY}/osd/osd.c" \
    $(pkg-config --libs gtk-layer-shell-0 gtk+-3.0)
install -Dm755 "${OVERLAY}/osd/osd" "${HOME}/.local/share/arktube-overlay/osd"
install -Dm755 "${OVERLAY}/osd/osd-notify.sh" "${HOME}/.local/share/arktube-overlay/osd-notify.sh"
# Re-running install.sh recompiles and redeploys this each time, same as
# every other file here -- there's no separate "rebuild the OSD" step to
# remember. The built binary itself (src/overlay/osd/osd) is left in the
# repo tree too, gitignored, purely so a re-run doesn't need network
# access to rebuild if apt's cache is already warm.

echo "==> Building and deploying the standalone power menu"
gcc -O2 -Wall \
    $(pkg-config --cflags gtk-layer-shell-0 gtk+-3.0) \
    -o "${HERE}/src/overlay/power-menu/power-menu" \
    "${OVERLAY}/power-menu/power-menu.c" \
    $(pkg-config --libs gtk-layer-shell-0 gtk+-3.0)
install -Dm755 "${OVERLAY}/power-menu/power-menu" "${HOME}/.local/share/arktube-overlay/power-menu"
# Same build-and-deploy-every-run pattern as the OSD immediately above,
# and same reason to be a separate binary from it (see power-menu.c's
# own top comment) rather than folded into osd.c: the OSD is a
# fire-and-forget toast with no user choice in it, this is a menu with
# real, destructive actions behind it -- keeping them as two small,
# independently-reasoned-about programs beats one bigger one where a
# mistake in the toast's code shares a process with the poweroff button.

cat <<'EOF'

==> Done.

Log out, click the gear icon on the GDM login screen, and select
"ARKtube". Authenticating from there should land in ARKtube fullscreen
with no manual steps.

Login/authentication, the gear menu itself, and returning to it on
logout are all handled by GDM — nothing above touches any of that; see
the root README's "Responsibilities" section for why that boundary is
deliberate.

Lock and logout are already wired and need no extra work here: overlay.py's
lock()/unlock()/logout() call loginctl and $XDG_SESSION_ID directly, which
are systemd/logind primitives, not GNOME-Kiosk-specific — that's why this
branch could salvage overlay.py byte-for-byte from webtop in the first
place. There is still no PIN/credential check behind Lock — see overlay.py's
own lock() docstring for why that's intentional, not an oversight.

Remote input (Menu/Power/Volume/Brightness/cursor auto-hide) is now
wired per docs/planning/REMOTE-INPUT-MAPPING.md. Two real bugs found
against physical hardware are fixed by this run:

  * Power used to shut the machine down instantly with no overlay —
    that was systemd-logind's own default handling of the physical
    key racing (and winning) against Sway's bindsym, not a bug in
    20-arktube.conf or overlay.py. Fixed by the `systemd-inhibit
    --what=handle-power-key --mode=block` wrapper 20-arktube.conf now
    execs overlay.py through — the same kind of logind inhibitor lock
    GNOME takes, held for as long as overlay.py runs. Takes effect
    the next time the ARKtube session starts (log out and back in, or
    reboot); no systemd restart needed for this one.
  * Brightness keys silently did nothing — brightnessctl needs `video`
    group membership to write to /sys/class/backlight/*, which this
    script now grants. Log out and back in for that to take effect;
    `groups` should list `video` afterward.

If Volume specifically still does nothing after logging back in,
that's not something this script can fix blindly — check, as this
project's own methodology elsewhere prefers, rather than guess:

  * `wpctl status` — confirms PipeWire/WirePlumber are actually
    running in this session and can see a sink at all.
  * Run `wpctl set-volume @DEFAULT_AUDIO_SINK@ 5%+` by hand in a
    terminal inside the ARKtube session — if that changes the level
    but the physical key still doesn't, the key isn't reaching Sway as
    the `XF86AudioRaiseVolume` keysym 20-arktube.conf binds; `wev` (or
    `sway -d`'s own key-event logging) shows what keysym, if any, the
    key actually sends.

Fixed by this run, but still worth confirming against a real display:

  * Whether Ubuntu's default swaybar shows through over ARKtube's
    fullscreen window — 20-arktube.conf now sets `bar { mode invisible }`
    defensively; see docs/bugs_caught/swaybar-top-layer-fullscreen.md
    for why this was closed without a real-display confirmation.
  * Idle-inhibit during playback (docs/foundational/SYSTEM_DESIGN.md) —
    this was documented as a success criterion but nothing actually
    requested an inhibitor anywhere in this branch. The
    `systemd-inhibit` wrapper 20-arktube.conf already execs overlay.py
    through now also takes logind's own `idle` inhibitor for the
    session's lifetime, not just `handle-power-key`; see
    docs/bugs_caught/idle-inhibit-gap.md. This is session-wide, not
    playback-scoped — ARKtube has no playback-state signal to key a
    narrower inhibitor off of yet.

Not yet resolved by this script, and worth checking against a real
display before calling this done:

  * Any TV-remote key that isn't a bare arrow/Enter/Escape/Home/F11 —
    Sway's default config only binds $mod-modified keys out of the box,
    which shouldn't collide with ARKtube's bare-key set (see
    docs/foundational/PROBLEM_STATEMENT.md's sibling doc on `webtop` for
    how that was checked for GNOME Kiosk's default bindings); this has
    not been re-checked against Sway's own default config the same way.

Run uninstall.sh (same directory as this script) to remove everything
install.sh sets up, if this ever needs to come back off a machine.
EOF
