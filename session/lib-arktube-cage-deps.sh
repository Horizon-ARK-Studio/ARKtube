# lib-arktube-cage-deps.sh — sourced by both install-arktube-cage-session.sh
# and uninstall-arktube-cage-session.sh so the dependency lists and the
# "was this already on the system before we touched it" bookkeeping live
# in exactly one place. Nothing in here is meant to be run directly.
#
# Why bookkeeping exists at all: this session's install script and
# ARKtube Webtop's install-webtop-session.sh (see ../../webtop's
# session/install-webtop-session.sh) both install the *same* overlay
# runtime packages, because they both deploy the same
# session/overlay/overlay.py. The two sessions are deliberately left
# installed side by side (see this script's own closing banner), so an
# uninstall of just this Cage session must never rip out a package the
# GNOME Kiosk session is still relying on -- and separately, must never
# remove a package that was already on the machine for some unrelated
# reason before this script ever ran. Both cases fall back to asking
# before touching anything; only packages this script can prove it
# installed itself, for itself, are removed without asking.

# --- Package lists -----------------------------------------------------

# Only needed to build Cage itself (meson/ninja/the -dev headers, plus
# wlroots's own transitive build deps -- see install script's own long
# comment on libwlroots-dev's role here). Never installed by webtop's
# script, so no sibling-session check is needed for these.
ARKTUBE_CAGE_BUILD_DEPS=(
    meson ninja-build pkg-config git scdoc
    libwayland-dev libxkbcommon-dev libdrm-dev
    libwlroots-dev
    libpixman-1-dev wayland-protocols libegl1-mesa-dev liblcms2-dev
    hwdata glslang-tools
)

# Installed by *both* this script and webtop's install-webtop-session.sh,
# because both run the same session/overlay/overlay.py. Removing one of
# these while the other session is still installed would break it.
ARKTUBE_CAGE_SHARED_OVERLAY_DEPS=(
    python3-pip python3-gi gir1.2-webkit2-4.1
    network-manager wireplumber pulseaudio-utils brightnessctl upower
)

# Only meaningful under this session -- see install script's comment on
# why webtop's own script never installed these (the overlay's
# layer-shell placement code is unreachable dead weight under GNOME
# Kiosk/Mutter). Still, treat them with the same sibling-session caution
# as the shared list above rather than assuming they're safe: if
# somehow a webtop checkout has since added the same gtk-layer-shell
# usage, this is the one place that assumption would need revisiting.
ARKTUBE_CAGE_LAYERSHELL_ONLY_DEPS=(
    gir1.2-gtklayershell-0.1 libgtk-layer-shell0
)

# pip packages installed with --user --break-system-packages. Also
# shared with webtop (same requirements.txt, copied verbatim -- see
# session/README.md).
ARKTUBE_CAGE_PIP_PACKAGES=(
    pywebview
)

# --- State locations -----------------------------------------------------

# System-level state (needs root to write, so it lives next to other
# root-owned package bookkeeping rather than under a user's home).
ARKTUBE_CAGE_STATE_DIR="/var/lib/arktube-cage-session"
ARKTUBE_CAGE_APT_STATE_FILE="${ARKTUBE_CAGE_STATE_DIR}/apt-packages.state"
ARKTUBE_CAGE_INSTALL_LOG_COPY="${ARKTUBE_CAGE_STATE_DIR}/meson-install-log.txt"

# User-level state (pip --user is per-user, so track it per-user too).
ARKTUBE_CAGE_USER_STATE_DIR="${HOME}/.local/share/arktube-cage-session"
ARKTUBE_CAGE_PIP_STATE_FILE="${ARKTUBE_CAGE_USER_STATE_DIR}/pip-packages.state"

# --- Detection helpers -----------------------------------------------------

# Is an apt package currently installed?
arktube_cage_apt_pkg_installed() {
    dpkg -s "$1" >/dev/null 2>&1
}

# Is a pip package currently installed for the invoking user?
arktube_cage_pip_pkg_installed() {
    python3 -m pip show --disable-pip-version-check "$1" >/dev/null 2>&1
}

# Is ARKtube Webtop's GNOME Kiosk session (the sibling this script is
# never supposed to disturb) installed on this machine right now?
# Checked by files that script's own install-webtop-session.sh deploys,
# not by which branch happens to be checked out here -- the two
# sessions install independently of what git worktree you're reading
# this from.
arktube_cage_webtop_session_installed() {
    [ -f /usr/share/wayland-sessions/arktube.desktop ] ||
        [ -f /usr/share/xsessions/arktube.desktop ] ||
        [ -x "${HOME}/.local/bin/gnome-kiosk-script" ]
}

# --- State file read/write -----------------------------------------------

# Record (or re-confirm) each apt package's provenance the first time
# this script sees it. "new" = this script installed it because it
# wasn't already on the system; "preexisting" = it was already present
# before this script touched anything. Re-running install keeps
# whatever the *first* run recorded rather than flipping it, since by
# the second run everything in the list is trivially "already
# installed" and that must not be misread as "we just installed this
# for you."
#
# Usage: arktube_cage_record_apt_status <package> <preexisting|new>
arktube_cage_record_apt_status() {
    local pkg="$1" status="$2"
    sudo mkdir -p "${ARKTUBE_CAGE_STATE_DIR}"
    if [ -f "${ARKTUBE_CAGE_APT_STATE_FILE}" ] && grep -qE "^${pkg}[[:space:]]" "${ARKTUBE_CAGE_APT_STATE_FILE}" 2>/dev/null; then
        return 0
    fi
    printf '%s %s\n' "${pkg}" "${status}" | sudo tee -a "${ARKTUBE_CAGE_APT_STATE_FILE}" >/dev/null
}

# Usage: arktube_cage_record_pip_status <package> <preexisting|new>
arktube_cage_record_pip_status() {
    local pkg="$1" status="$2"
    mkdir -p "${ARKTUBE_CAGE_USER_STATE_DIR}"
    if [ -f "${ARKTUBE_CAGE_PIP_STATE_FILE}" ] && grep -qE "^${pkg}[[:space:]]" "${ARKTUBE_CAGE_PIP_STATE_FILE}" 2>/dev/null; then
        return 0
    fi
    printf '%s %s\n' "${pkg}" "${status}" >> "${ARKTUBE_CAGE_PIP_STATE_FILE}"
}

# Usage: arktube_cage_apt_status <package>  -->  echoes "new",
# "preexisting", or "unknown" (no record -- e.g. this machine's install
# predates this bookkeeping, or the state file was lost).
arktube_cage_apt_status() {
    local pkg="$1"
    if [ -f "${ARKTUBE_CAGE_APT_STATE_FILE}" ]; then
        awk -v p="${pkg}" '$1 == p { print $2; found=1 } END { if (!found) print "unknown" }' \
            "${ARKTUBE_CAGE_APT_STATE_FILE}"
    else
        echo "unknown"
    fi
}

arktube_cage_pip_status() {
    local pkg="$1"
    if [ -f "${ARKTUBE_CAGE_PIP_STATE_FILE}" ]; then
        awk -v p="${pkg}" '$1 == p { print $2; found=1 } END { if (!found) print "unknown" }' \
            "${ARKTUBE_CAGE_PIP_STATE_FILE}"
    else
        echo "unknown"
    fi
}
