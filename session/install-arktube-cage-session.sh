#!/usr/bin/env bash
#
# install-arktube-cage-session.sh — build this branch's Cage (the one
# with wlr-layer-shell-v1 support added, see layer_shell.c/.h) and wire
# it up as a selectable GDM session running ARKtube, replacing ARKtube
# Webtop's GNOME Kiosk session rather than sitting alongside it. See
# docs/foundational/CAGE-MIGRATION.md (on the webtop branch) for the
# staged plan this collapses into one script for an initial testable
# build -- Stages 9 (feasibility) and 10 (selectable session) below,
# specifically; Stages 11-14 (lifecycle/input/hardening re-verification,
# and actually removing GNOME Kiosk from webtop) are still follow-up
# work once this has been run and exercised on real hardware.
#
# Requires: ARKtube already built/installed from main (so `arktube_linux`
# is on PATH -- see main's arktube_linux/CMakeLists.txt). This script
# does not build or install ARKtube itself, same boundary ARKtube
# Webtop's own install-webtop-session.sh drew.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/.." && pwd)"

if ! command -v arktube_linux >/dev/null 2>&1; then
    echo "warning: 'arktube_linux' is not on PATH yet." >&2
    echo "         Build and install ARKtube from main first, then re-run this script." >&2
fi

echo "==> Installing Cage's build dependencies"
# wlroots-0.20 is what this branch's meson.build asks for (see its
# dependency() call). Ubuntu Noble's own libwlroots-dev is 0.17 -- too
# old to satisfy that by pkg-config name alone -- so subprojects/wlroots.wrap
# (added alongside this script) makes meson build wlroots 0.20 from
# source automatically the first time `meson setup` runs below, the
# moment the system package lookup for wlroots-0.20 fails. That first
# run needs network access on this machine (a one-time clone of
# wlroots itself); see the wrap file's own comment for details.
#
# libwlroots-dev is still installed here even though its own wlroots
# won't be the one actually used: on Ubuntu/Debian it's a convenient,
# already-solved way to pull in the *rest* of wlroots's own build
# dependencies (libinput, libseat, libdrm, EGL/GBM, Vulkan headers,
# libdisplay-info, libliftoff) transitively, rather than this script
# maintaining its own separate list of them that would drift from
# whatever the packaged wlroots actually depends on. A handful of
# packages wlroots 0.20 needs that 0.17's dependency list doesn't pull
# in are listed explicitly below it instead.
sudo apt-get update
sudo apt-get install -y \
    meson ninja-build pkg-config git scdoc \
    libwayland-dev libxkbcommon-dev libdrm-dev \
    libwlroots-dev \
    libpixman-1-dev wayland-protocols libegl1-mesa-dev liblcms2-dev \
    hwdata glslang-tools

echo "==> Building Cage (this branch: layer-shell-enabled fork)"
cd "${REPO_ROOT}"
meson setup build --buildtype=release --wipe
meson compile -C build
sudo meson install -C build
cd "${HERE}"

echo "==> Adding a Cage-based gear-menu entry named 'ARKtube (Cage)'"
sudo install -Dm644 "${HERE}/wayland-sessions/arktube-cage.desktop" \
    /usr/share/wayland-sessions/arktube-cage.desktop

echo "==> Deploying the ARKtube-under-Cage session script"
sudo install -Dm755 "${HERE}/cage/arktube-cage-session" \
    /usr/local/bin/arktube-cage-session

# Stage 8's TV-style system overlay (see webtop's own
# docs/STAGE-8-TV-STYLE-OVERLAY.md), carried over onto Cage: same
# runtime dependencies webtop's install-webtop-session.sh already
# listed for it, PLUS gir1.2-gtklayershell-0.1/libgtk-layer-shell0,
# which that script never actually installed even though overlay.py
# has always imported `gi.repository.GtkLayerShell` -- a gap that only
# didn't matter under GNOME Kiosk because the layer-shell placement
# code was unreachable dead weight there (see the module docstring:
# under GNOME Kiosk/Mutter, xdg_toplevel's x=0/y=0/on_top hints don't
# work either, so the overlay was already only cosmetically placed).
# It matters here, since this session is the one layer-shell placement
# is actually for.
echo "==> Installing the system overlay's dependencies"
sudo apt-get install -y \
    python3-pip python3-gi gir1.2-webkit2-4.1 \
    gir1.2-gtklayershell-0.1 libgtk-layer-shell0 \
    network-manager wireplumber pulseaudio-utils brightnessctl upower
pip install --user --break-system-packages -r "${HERE}/overlay/requirements.txt"

echo "==> Deploying the system overlay"
mkdir -p "${HOME}/.local/share/arktube-overlay/static"
install -Dm755 "${HERE}/overlay/overlay.py" "${HOME}/.local/share/arktube-overlay/overlay.py"
install -Dm644 "${HERE}/overlay/static/index.html" "${HOME}/.local/share/arktube-overlay/static/index.html"
install -Dm644 "${HERE}/overlay/static/style.css" "${HOME}/.local/share/arktube-overlay/static/style.css"
install -Dm644 "${HERE}/overlay/static/app.js" "${HOME}/.local/share/arktube-overlay/static/app.js"

cat <<'EOF'

==> Done.

Log out, and at GDM's login screen pick the gear icon -> "ARKtube (Cage)".

This intentionally does NOT touch ARKtube Webtop's existing GNOME Kiosk
session (gnome-kiosk, gnome-kiosk-script-session, the "ARKtube" gear
entry that runs it) -- both are left installed side by side so you can
compare them, the same "don't cut over until proven" caution
docs/foundational/CAGE-MIGRATION.md asks for. Removing the GNOME Kiosk
path once this one is verified is Stage 14's job, not this script's.
EOF
