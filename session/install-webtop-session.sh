#!/usr/bin/env bash
#
# install-webtop-session.sh — Stage 1 (selectable session) + Stage 2
# (session lifecycle): make ARKtube selectable from GDM's gear icon, land
# in it end to end, and make sure exiting it ends the session instead of
# relaunching it forever. See docs/STAGE-1-SELECTABLE-SESSION.md and
# docs/STAGE-2-SESSION-LIFECYCLE.md for what each part below is fixing
# and how it was verified.
#
# Requires: ARKtube already installed as a .deb (so `arktube` is on
# PATH — see main's packaging/linux/build-deb.sh). This script does not
# build or install ARKtube itself; see the root README's "Responsibilities"
# section for why that boundary matters.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v arktube >/dev/null 2>&1; then
    echo "warning: 'arktube' is not on PATH yet." >&2
    echo "         Install ARKtube's .deb first, then re-run this script." >&2
fi

echo "==> Installing GNOME Kiosk + the script-session package"
sudo apt-get update
sudo apt-get install -y gnome-kiosk gnome-kiosk-script-session

# gnome-kiosk-script-session already ships xsessions/wayland-sessions
# .desktop entries, a gnome-session session file, and a systemd user
# service — all under paths owned by that package. Rather than edit
# those in place (which apt/dpkg will flag as a modified conffile on
# the package's next update), we add sibling .desktop files under the
# same directories, with a different filename and Name=ARKtube, that
# point at the *same* gnome-session session id (gnome-kiosk-script).
# Both entries will show up in the gear menu; this one is the one meant
# to actually be used.
echo "==> Adding a gear-menu entry named ARKtube"
sudo install -Dm644 "${HERE}/wayland-sessions/arktube.desktop" \
    /usr/share/wayland-sessions/arktube.desktop
sudo install -Dm644 "${HERE}/xsessions/arktube.desktop" \
    /usr/share/xsessions/arktube.desktop

# ~/.local/bin/gnome-kiosk-script is the one file the package leaves
# unfilled (see /usr/bin/gnome-kiosk-script's own logic, and
# docs/STAGE-1-SELECTABLE-SESSION.md for how that was confirmed).
echo "==> Deploying ARKtube's gnome-kiosk-script"
install -Dm755 "${HERE}/gnome-kiosk-script" "${HOME}/.local/bin/gnome-kiosk-script"

# org.gnome.Kiosk.Script.service (owned by gnome-kiosk-script-session) ships
# Restart=always with no RestartSec override. On Noble's gnome-kiosk 46,
# that turns ARKtube exiting — a crash, or a deliberate quit — into an
# unremovable relaunch loop instead of ending the session and returning to
# GDM. GNOME Kiosk 50 fixed this upstream ("Do not restart the script
# session on exit (allows logout)"); this drop-in reproduces that fix for
# Noble without touching the package's own unit file. See
# docs/STAGE-2-SESSION-LIFECYCLE.md for how this was confirmed against the
# actual shipped unit file.
echo "==> Overriding org.gnome.Kiosk.Script.service's Restart=always"
mkdir -p "${HOME}/.config/systemd/user/org.gnome.Kiosk.Script.service.d"
install -Dm644 \
    "${HERE}/systemd/org.gnome.Kiosk.Script.service.d/override.conf" \
    "${HOME}/.config/systemd/user/org.gnome.Kiosk.Script.service.d/override.conf"
systemctl --user daemon-reload 2>/dev/null || true

# Stage 6 (see docs/STAGE-6-OFFLINE-TOPBAR.md): the offline system topbar.
# pywebview's GTK backend needs PyGObject and WebKit2GTK from apt — pip
# alone can't provide those. The tray/control CLI tools it shells out to
# (nmcli, wpctl, brightnessctl, upower) are each optional at runtime —
# see topbar.py's `run()` — but are installed here too so the common case
# works out of the box rather than silently degrading on a fresh Noble
# install.
echo "==> Installing the offline topbar's system dependencies"
sudo apt-get install -y \
    python3-pip python3-gi gir1.2-webkit2-4.1 \
    network-manager wireplumber pulseaudio-utils brightnessctl upower
pip install --user --break-system-packages -r "${HERE}/topbar/requirements.txt"

echo "==> Deploying the offline topbar"
mkdir -p "${HOME}/.local/share/arktube-topbar/static"
install -Dm755 "${HERE}/topbar/topbar.py" "${HOME}/.local/share/arktube-topbar/topbar.py"
install -Dm644 "${HERE}/topbar/static/index.html" "${HOME}/.local/share/arktube-topbar/static/index.html"
install -Dm644 "${HERE}/topbar/static/style.css" "${HOME}/.local/share/arktube-topbar/static/style.css"
install -Dm644 "${HERE}/topbar/static/app.js" "${HOME}/.local/share/arktube-topbar/static/app.js"

# Stage 7 (see docs/STAGE-7-VISIBILITY-AND-CURSOR.md): auto-hide the
# mouse pointer after 10s idle, and let the topbar auto-reveal itself
# when main's Immersive Mode is off or the machine is offline. The
# X11-Xfixes cursor-hider is the only new system dependency this stage
# adds; the visibility half is pure logic already deployed above as
# part of topbar.py, nothing extra to install for it.
echo "==> Installing the cursor auto-hide tool"
sudo apt-get install -y unclutter-xfixes

cat <<'EOF'

==> Done.

Log out, click the gear icon on the GDM login screen, and select
"ARKtube". Authenticating from there should land in ARKtube fullscreen
with no manual steps — that's Stage 1's exit condition.

Exiting ARKtube (crash or deliberate quit) should now end the session and
return to the GDM login screen instead of relaunching ARKtube in a loop —
that's Stage 2's exit condition for the "ARKtube exits on its own" case.
Locking is not yet wired — see docs/STAGE-2-SESSION-LIFECYCLE.md for why
that one is still open.

Arrow keys, Enter, Escape, Home, and F11 (ARKtube's own keyboard/gamepad
input set) are not grabbed by anything GNOME Kiosk or its dependencies
bind by default, so no extra input configuration is installed here — see
docs/STAGE-3-INPUT-MAPPING.md for how that was confirmed.

Not yet verified end to end in this environment (no display manager
here to click through) — see docs/STAGE-1-SELECTABLE-SESSION.md,
docs/STAGE-2-SESSION-LIFECYCLE.md, and docs/STAGE-3-INPUT-MAPPING.md for
exactly what has and hasn't been confirmed.

A system topbar now starts alongside ARKtube (clock, Wi-Fi, volume,
brightness, battery, lock, and power), reachable without a network
connection. See docs/STAGE-6-OFFLINE-TOPBAR.md for what backs each
control and what's still open.

The topbar now also hides itself while ARKtube's own Immersive Mode is
on and the machine is online, and auto-reveals itself the instant
either stops being true (Immersive Mode turned off, or connectivity
drops) -- so it's never actually out of reach. The mouse pointer hides
itself after 10s of no movement or clicks, system-wide. See
docs/STAGE-7-VISIBILITY-AND-CURSOR.md for both, and their current
X11-only caveat on a pure-Wayland session.
EOF
