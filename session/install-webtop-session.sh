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
EOF
