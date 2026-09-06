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
#
# Every apt/pip package this script installs is recorded in
# lib-arktube-cage-deps.sh's state files as either "new" (wasn't on the
# system before this script ran) or "preexisting" (already was). See
# uninstall-arktube-cage-session.sh, which reads that record back to
# decide what it can safely remove on its own versus what it must ask
# about -- that script is this one's exact undo, not a separate design.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${HERE}/.." && pwd)"

# shellcheck source=lib-arktube-cage-deps.sh
source "${HERE}/lib-arktube-cage-deps.sh"

if ! command -v arktube_linux >/dev/null 2>&1; then
    echo "warning: 'arktube_linux' is not on PATH yet." >&2
    echo "         Build and install ARKtube from main first, then re-run this script." >&2
fi

# Snapshot which packages are already present *before* apt-get touches
# anything, package by package, across every list this script might
# install from. This is the only reliable way to know afterwards which
# ones apt-get installed on our behalf versus which were already there
# for some other reason -- `apt-get install` itself doesn't tell you
# that once it's done, and apt-mark's auto/manual flag answers a
# different question (how it got there) than the one the uninstall
# script needs (would removing it take away something that predates
# this script).
echo "==> Recording pre-install package state (for a clean uninstall later)"
ALL_APT_DEPS=(
    "${ARKTUBE_CAGE_BUILD_DEPS[@]}"
    "${ARKTUBE_CAGE_SHARED_OVERLAY_DEPS[@]}"
    "${ARKTUBE_CAGE_LAYERSHELL_ONLY_DEPS[@]}"
)
declare -A PKG_WAS_PRESENT
for pkg in "${ALL_APT_DEPS[@]}"; do
    if arktube_cage_apt_pkg_installed "${pkg}"; then
        PKG_WAS_PRESENT["${pkg}"]=1
    else
        PKG_WAS_PRESENT["${pkg}"]=0
    fi
done
declare -A PIP_WAS_PRESENT
for pkg in "${ARKTUBE_CAGE_PIP_PACKAGES[@]}"; do
    if arktube_cage_pip_pkg_installed "${pkg}"; then
        PIP_WAS_PRESENT["${pkg}"]=1
    else
        PIP_WAS_PRESENT["${pkg}"]=0
    fi
done

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
sudo apt-get install -y "${ARKTUBE_CAGE_BUILD_DEPS[@]}"

echo "==> Building Cage (this branch: layer-shell-enabled fork)"
cd "${REPO_ROOT}"
meson setup build --buildtype=release --wipe
meson compile -C build
sudo meson install -C build
cd "${HERE}"

# meson's ninja backend writes exactly what got installed to
# build/meson-logs/install-log.txt. Copying it into this script's own
# state directory means uninstall-arktube-cage-session.sh can still
# find it (via `ninja -C build uninstall`, which reads it directly out
# of the build dir) for as long as `build/` exists, and has a record to
# point at even if `build/` is later deleted before uninstalling.
sudo mkdir -p "${ARKTUBE_CAGE_STATE_DIR}"
if [ -f "${REPO_ROOT}/build/meson-logs/install-log.txt" ]; then
    sudo cp "${REPO_ROOT}/build/meson-logs/install-log.txt" "${ARKTUBE_CAGE_INSTALL_LOG_COPY}"
fi
# Also record where this checkout lives, so uninstall can find `build/`
# even if it's invoked from a copy of just the session/ directory.
echo "${REPO_ROOT}" | sudo tee "${ARKTUBE_CAGE_STATE_DIR}/repo-root.txt" >/dev/null

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
    "${ARKTUBE_CAGE_SHARED_OVERLAY_DEPS[@]}" \
    "${ARKTUBE_CAGE_LAYERSHELL_ONLY_DEPS[@]}"
pip install --user --break-system-packages -r "${HERE}/overlay/requirements.txt"

# Now that apt-get/pip have actually run, write down what was new versus
# what was already there -- see the "Recording pre-install package
# state" step above for why this has to be a before/after diff rather
# than something inferred after the fact.
for pkg in "${ALL_APT_DEPS[@]}"; do
    if [ "${PKG_WAS_PRESENT[${pkg}]}" = "1" ]; then
        arktube_cage_record_apt_status "${pkg}" "preexisting"
    else
        arktube_cage_record_apt_status "${pkg}" "new"
    fi
done
for pkg in "${ARKTUBE_CAGE_PIP_PACKAGES[@]}"; do
    if [ "${PIP_WAS_PRESENT[${pkg}]}" = "1" ]; then
        arktube_cage_record_pip_status "${pkg}" "preexisting"
    else
        arktube_cage_record_pip_status "${pkg}" "new"
    fi
done

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

To remove everything this script just did, run
./uninstall-arktube-cage-session.sh from this same directory. It reads
the package-provenance record this run just wrote, so it can tell which
dependencies it's safe to remove on its own (installed new, just now,
for this session) from which ones it should ask you about first
(already on this machine before this script ran, or still in use by
the GNOME Kiosk session above).
EOF
