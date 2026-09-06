#!/usr/bin/env bash
#
# uninstall-arktube-cage-session.sh — the exact undo of
# install-arktube-cage-session.sh: removes the Cage build, the GDM gear
# entry, the session launch script, and (carefully -- see below) the
# dependencies that install script installed.
#
# Dependency removal policy (this is the part worth reading before
# running it):
#
#   - A package this machine did NOT have before
#     install-arktube-cage-session.sh ran, and which nothing else on
#     this machine still needs (see the sibling-session check below) --
#     removed automatically, no prompt. It was installed solely for
#     this app, so removing it undoes exactly what installing it did.
#   - Anything else -- a package that was already on this machine
#     before that script ran, a package this script has no provenance
#     record for at all (e.g. the install predates this bookkeeping),
#     or a package ARKtube Webtop's GNOME Kiosk session is still
#     relying on -- is only ever removed after you say yes. This script
#     will list exactly what it wants to remove and why before it asks.
#
# This script only ever runs `apt-get remove`/`pip uninstall` on the
# specific packages install-arktube-cage-session.sh itself installed
# (see lib-arktube-cage-deps.sh's package lists) -- never a general
# `apt-get autoremove` or `apt-get purge` sweep, and never anything
# outside those lists. It will suggest running `apt-get autoremove`
# yourself at the end, for apt's own transitively-pulled packages,
# rather than run that broader sweep on your behalf.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck source=lib-arktube-cage-deps.sh
source "${HERE}/lib-arktube-cage-deps.sh"

DRY_RUN=0
ASSUME_YES=0
for arg in "$@"; do
    case "${arg}" in
        --dry-run) DRY_RUN=1 ;;
        -y|--yes)  ASSUME_YES=1 ;;
        -h|--help)
            cat <<EOF
Usage: $(basename "$0") [--dry-run] [-y|--yes]

  --dry-run   Print what would be removed without removing anything.
  -y, --yes   Don't ask before removing packages that were already on
              this machine before install-arktube-cage-session.sh ran
              (packages installed solely for this app are still
              removed without asking, exactly as with no flag).
EOF
            exit 0
            ;;
        *)
            echo "unknown option: ${arg} (see --help)" >&2
            exit 1
            ;;
    esac
done

run() {
    if [ "${DRY_RUN}" = "1" ]; then
        echo "[dry-run] $*"
    else
        "$@"
    fi
}

confirm() {
    # confirm <prompt>. Returns success (0) iff the user says yes, or
    # -y/--yes was passed. Always asks for real in --dry-run mode too
    # (dry-run only suppresses actually running commands, not the
    # decision logic that decides which ones it would run).
    local prompt="$1"
    if [ "${ASSUME_YES}" = "1" ]; then
        echo "${prompt} [auto-yes: -y passed]"
        return 0
    fi
    local reply
    read -r -p "${prompt} [y/N] " reply </dev/tty
    [[ "${reply}" =~ ^[Yy]$ ]]
}

WEBTOP_INSTALLED=0
if arktube_cage_webtop_session_installed; then
    WEBTOP_INSTALLED=1
    echo "==> Detected ARKtube Webtop's GNOME Kiosk session is also installed."
    echo "    Anything the two sessions share will be left alone."
fi

# --- 1. Cage itself -------------------------------------------------------

REPO_ROOT=""
if [ -f "${ARKTUBE_CAGE_STATE_DIR}/repo-root.txt" ]; then
    REPO_ROOT="$(cat "${ARKTUBE_CAGE_STATE_DIR}/repo-root.txt")"
elif [ -f "${HERE}/../meson.build" ]; then
    REPO_ROOT="$(cd "${HERE}/.." && pwd)"
fi

echo "==> Removing the Cage build installed by 'sudo meson install -C build'"
if [ -n "${REPO_ROOT}" ] && [ -d "${REPO_ROOT}/build" ]; then
    # ninja's generated 'uninstall' target reads build/meson-logs/install-log.txt
    # and removes precisely the files 'meson install' put down -- no
    # guessing at paths, no risk of catching something unrelated that
    # happens to live in the same prefix.
    ( cd "${REPO_ROOT}" && run sudo ninja -C build uninstall )
else
    echo "    warning: no build/ directory found (checked: ${REPO_ROOT:-<unknown repo root>})."
    if [ -f "${ARKTUBE_CAGE_INSTALL_LOG_COPY}" ]; then
        echo "    A copy of the original install log is at:"
        echo "      ${ARKTUBE_CAGE_INSTALL_LOG_COPY}"
        echo "    Remove the files it lists by hand, or re-run"
        echo "    'meson setup build && sudo ninja -C build uninstall' from a"
        echo "    fresh checkout of this branch to regenerate build/ first."
    else
        echo "    No install-log record was found either (this uninstall script may"
        echo "    predate the install it's undoing). Not guessing at paths to remove --"
        echo "    if Cage was installed to the default prefix, it's likely at"
        echo "    /usr/local/bin/cage and /usr/local/share/man/man1/cage.1; verify before"
        echo "    removing anything by hand."
    fi
fi

# --- 2. GDM gear entry + session launch script (ours alone, never shared) --

echo "==> Removing the GDM gear-menu entry and session launch script"
[ -f /usr/share/wayland-sessions/arktube-cage.desktop ] &&
    run sudo rm -f /usr/share/wayland-sessions/arktube-cage.desktop
[ -f /usr/local/bin/arktube-cage-session ] &&
    run sudo rm -f /usr/local/bin/arktube-cage-session

# --- 3. The system overlay deployment --------------------------------------

# ~/.local/share/arktube-overlay is the *same* directory webtop's own
# install-webtop-session.sh deploys to (see session/README.md -- the
# overlay is vendored unmodified between the two branches). If that
# session is still installed, its session script still execs
# overlay.py out of this exact path -- deleting it here would silently
# break the sibling session the very next time someone logs into it.
echo "==> Removing the deployed system overlay"
if [ "${WEBTOP_INSTALLED}" = "1" ]; then
    echo "    Skipping ~/.local/share/arktube-overlay -- ARKtube Webtop's GNOME"
    echo "    Kiosk session is still installed and uses this same directory."
else
    [ -d "${HOME}/.local/share/arktube-overlay" ] &&
        run rm -rf "${HOME}/.local/share/arktube-overlay"
fi

# --- 4. pip packages --------------------------------------------------------

echo "==> Reviewing pip packages"
for pkg in "${ARKTUBE_CAGE_PIP_PACKAGES[@]}"; do
    if ! arktube_cage_pip_pkg_installed "${pkg}"; then
        continue
    fi
    if [ "${WEBTOP_INSTALLED}" = "1" ]; then
        echo "    '${pkg}': still needed by ARKtube Webtop's GNOME Kiosk session -- leaving it installed."
        continue
    fi
    status="$(arktube_cage_pip_status "${pkg}")"
    case "${status}" in
        new)
            echo "    '${pkg}': installed solely for this app -- removing."
            run python3 -m pip uninstall -y --break-system-packages "${pkg}"
            ;;
        preexisting|unknown)
            if [ "${status}" = "unknown" ]; then
                reason="no install record was found for it (this uninstall may predate that install)"
            else
                reason="it was already installed before install-arktube-cage-session.sh ran"
            fi
            if confirm "    '${pkg}': ${reason}. Remove it anyway?"; then
                run python3 -m pip uninstall -y --break-system-packages "${pkg}"
            else
                echo "    Leaving '${pkg}' installed."
            fi
            ;;
    esac
done

# --- 5. apt packages ---------------------------------------------------------

# Packages that are never shared with the GNOME Kiosk session -- only
# this app's own provenance record matters for these.
remove_apt_pkg_unshared() {
    local pkg="$1"
    if ! arktube_cage_apt_pkg_installed "${pkg}"; then
        return
    fi
    local status
    status="$(arktube_cage_apt_status "${pkg}")"
    case "${status}" in
        new)
            echo "    '${pkg}': installed solely for this app -- removing."
            run sudo apt-get remove -y "${pkg}"
            ;;
        preexisting|unknown)
            local reason
            if [ "${status}" = "unknown" ]; then
                reason="no install record was found for it"
            else
                reason="it was already installed before install-arktube-cage-session.sh ran"
            fi
            if confirm "    '${pkg}': ${reason}. Remove it anyway?"; then
                run sudo apt-get remove -y "${pkg}"
            else
                echo "    Leaving '${pkg}' installed."
            fi
            ;;
    esac
}

# Packages that ARE shared with the GNOME Kiosk session's own overlay
# install -- these get the extra "is the sibling session still here"
# gate in front of the same new/preexisting logic.
remove_apt_pkg_shared() {
    local pkg="$1"
    if ! arktube_cage_apt_pkg_installed "${pkg}"; then
        return
    fi
    if [ "${WEBTOP_INSTALLED}" = "1" ]; then
        echo "    '${pkg}': still needed by ARKtube Webtop's GNOME Kiosk session -- leaving it installed."
        return
    fi
    remove_apt_pkg_unshared "${pkg}"
}

echo "==> Reviewing Cage's build dependencies"
for pkg in "${ARKTUBE_CAGE_BUILD_DEPS[@]}"; do
    remove_apt_pkg_unshared "${pkg}"
done

echo "==> Reviewing the system overlay's dependencies"
for pkg in "${ARKTUBE_CAGE_SHARED_OVERLAY_DEPS[@]}"; do
    remove_apt_pkg_shared "${pkg}"
done
for pkg in "${ARKTUBE_CAGE_LAYERSHELL_ONLY_DEPS[@]}"; do
    remove_apt_pkg_shared "${pkg}"
done

# --- 6. Local build cache (not a system package -- just files in this checkout) --

if [ -n "${REPO_ROOT}" ] && [ -d "${REPO_ROOT}/build" ]; then
    if confirm "==> Also delete the local build/ directory (${REPO_ROOT}/build, includes the cloned wlroots subproject)?"; then
        run rm -rf "${REPO_ROOT}/build"
    fi
fi

# --- 7. This script's own state -------------------------------------------

if confirm "==> Delete this script's own install-provenance records (${ARKTUBE_CAGE_STATE_DIR}, ${ARKTUBE_CAGE_USER_STATE_DIR})?"; then
    run sudo rm -rf "${ARKTUBE_CAGE_STATE_DIR}"
    run rm -rf "${ARKTUBE_CAGE_USER_STATE_DIR}"
fi

cat <<EOF

==> Done.

Nothing outside the package lists in lib-arktube-cage-deps.sh was
touched -- in particular, no general 'apt-get autoremove' or
'apt-get purge' was run. If removing the packages above leaves other
packages apt marked as auto-installed with nothing left depending on
them, apt itself can tell you (and clean them up) with:

    apt-get autoremove --dry-run   # see what it would remove first
    sudo apt-get autoremove        # then actually remove it

That's apt's own dependency accounting, which is more reliable for
transitive packages than anything this script could reconstruct by
hand -- deliberately left to you rather than run automatically here.
EOF
