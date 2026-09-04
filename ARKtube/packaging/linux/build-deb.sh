#!/usr/bin/env bash
#
# build-deb.sh - reproducible ARKtube .deb build.
#
# Mirrors build-appimage.sh: builds with `neu build --embed-resources`
# (see docs/BUGS-CAUGHT.md) so there's no separate resources.neu to lose
# track of, then lays the binary + desktop entry + icon out under a
# debian/ control tree and packs it with dpkg-deb.
#
# Usage:
#   cd ARKtube/
#   neu update                      # pull the pinned 6.8.0 binaries
#   ./packaging/linux/build-deb.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PACKAGING_DIR="${ROOT_DIR}/packaging/linux"
VERSION="$(grep -o '"version": *"[^"]*"' "${ROOT_DIR}/neutralino.config.json" | head -1 | sed 's/.*"version": *"\(.*\)"/\1/')"
ARCH="amd64"
PKG_NAME="arktube"
BUILD_DIR="${ROOT_DIR}/.deb-build"
PKGROOT="${BUILD_DIR}/${PKG_NAME}_${VERSION}_${ARCH}"
OUTPUT="${ROOT_DIR}/ARKtube-${VERSION}-${ARCH}.deb"

echo "==> Cleaning previous build directory"
rm -rf "${BUILD_DIR}"
mkdir -p "${PKGROOT}/DEBIAN"
mkdir -p "${PKGROOT}/usr/bin"
mkdir -p "${PKGROOT}/usr/lib/arktube"
mkdir -p "${PKGROOT}/usr/share/applications"
mkdir -p "${PKGROOT}/usr/share/icons/hicolor/512x512/apps"

echo "==> Building with neu (embedding resources into the binary)"
cd "${ROOT_DIR}"
neu build --embed-resources

BIN_SRC="${ROOT_DIR}/dist/ARKtube/ARKtube-linux_x64"
if [[ ! -f "${BIN_SRC}" ]]; then
    echo "error: expected build output not found at ${BIN_SRC}" >&2
    echo "       (check 'neu build' output above for the actual path)" >&2
    exit 1
fi

echo "==> Assembling package tree"
install -Dm755 "${BIN_SRC}" "${PKGROOT}/usr/lib/arktube/ARKtube"
install -Dm755 "${PACKAGING_DIR}/embed-chrome.sh" "${PKGROOT}/usr/lib/arktube/embed-chrome.sh"
install -Dm644 "${ROOT_DIR}/resources/icons/appIcon.png" \
    "${PKGROOT}/usr/share/icons/hicolor/512x512/apps/arktube.png"

# A thin launcher in usr/bin, rather than symlinking straight to the
# binary, so NL_PATH can be pointed at a writable per-user data dir the
# same way packaging/linux/AppRun does for the AppImage build - a .deb
# install lands under /usr, which is read-only for the running user.
#
# Mirrors AppRun's hybrid-mode launch: Neutralino runs in plain window
# mode and owns the real top-level window; on X11, with the needed tools
# and a Chrome/Chromium binary present, /usr/lib/arktube/embed-chrome.sh
# spawns a real Chrome process and reparents it inside that window (see
# that script for the X11 reparenting + global F11/Escape/Home hotkey
# details), as a direct child of this launcher rather than a
# fully-detached process Neutralino itself owns, so the close-button
# lifecycle coupling below only needs to watch, not un-orphan, it. On
# Wayland (or without those tools/Chrome), it instead launches Neutralino
# with --native-fallback so its own webview loads YouTube directly - see
# docs/bugs-caught/BUGS-CAUGHT.md §10.
cat > "${PKGROOT}/usr/bin/arktube" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
DATA_HOME="${XDG_DATA_HOME:-${HOME}/.local/share}"
ARKTUBE_DATA_DIR="${DATA_HOME}/ARKtube"
mkdir -p "${ARKTUBE_DATA_DIR}"

CHROME_PROFILE_DIR="${ARKTUBE_DATA_DIR}/.tmp/chromedata"
CHROME_LOCK_PATTERN="--user-data-dir=${CHROME_PROFILE_DIR}"

reap_stale_chrome() {
    if command -v pgrep >/dev/null 2>&1; then
        local pids
        pids="$(pgrep -f -- "${CHROME_LOCK_PATTERN}" 2>/dev/null || true)"
        if [ -n "${pids}" ]; then
            echo "ARKtube: cleaning up a leftover browser process from a previous run..." >&2
            # shellcheck disable=SC2086
            kill -TERM ${pids} 2>/dev/null || true
            sleep 1
            # shellcheck disable=SC2086
            kill -KILL ${pids} 2>/dev/null || true
        fi
    fi
    rm -f "${CHROME_PROFILE_DIR}/SingletonLock" \
          "${CHROME_PROFILE_DIR}/SingletonSocket" \
          "${CHROME_PROFILE_DIR}/SingletonCookie" 2>/dev/null || true
}

reap_stale_chrome
trap reap_stale_chrome EXIT INT TERM

# --- Immersive Mode: real Chrome-side hardening -------------------------
# resources/js/app-init.js's Immersive Mode button persists its on/off
# state via Neutralino.storage, a plain file at
# ${ARKTUBE_DATA_DIR}/.storage/<key>.neustorage; this launcher reads that
# same file directly and decides whether embed-chrome.sh gets the
# --immersive flag for this launch.
IMMERSIVE_FLAG_FILE="${ARKTUBE_DATA_DIR}/.storage/immersiveMode.neustorage"
IMMERSIVE_ARG=""
if [ -f "${IMMERSIVE_FLAG_FILE}" ] && [ "$(cat "${IMMERSIVE_FLAG_FILE}" 2>/dev/null)" = "1" ]; then
    IMMERSIVE_ARG="--immersive"
fi

# --- Decide, up front, whether the X11 hybrid embed is even possible -----
# See packaging/linux/AppRun for the full explanation and
# docs/bugs-caught/BUGS-CAUGHT.md §11. xdotool/wmctrl/xbindkeys are only
# Recommends (not Depends) on this package precisely so a Wayland-only
# desktop isn't forced to install X11 tooling it will never use - so this
# check still has to happen at runtime, not just at install time. This
# checks DISPLAY (works for both a real X11 session and Xwayland under
# Wayland), not XDG_SESSION_TYPE - the embed is the default on Wayland
# too now, as long as Xwayland is actually running.
EMBED_SUPPORTED=1
if [ -z "${DISPLAY:-}" ]; then
    EMBED_SUPPORTED=0
fi
if [ "${EMBED_SUPPORTED}" = "1" ]; then
    for tool in xdotool wmctrl xbindkeys; do
        if ! command -v "${tool}" >/dev/null 2>&1; then
            EMBED_SUPPORTED=0
            break
        fi
    done
fi
if [ "${EMBED_SUPPORTED}" = "1" ] && ! xdotool getdisplaygeometry >/dev/null 2>&1; then
    EMBED_SUPPORTED=0
fi
CHROME_CANDIDATE=""
if [ "${EMBED_SUPPORTED}" = "1" ]; then
    EMBED_SUPPORTED=0
    for candidate in google-chrome-stable google-chrome chromium chromium-browser; do
        if command -v "${candidate}" >/dev/null 2>&1; then
            EMBED_SUPPORTED=1
            CHROME_CANDIDATE="${candidate}"
            break
        fi
    done
fi

# --- Redirect the profile dir if that Chrome/Chromium is snap-confined ---
# See packaging/linux/AppRun for the full explanation and
# docs/bugs-caught/BUGS-CAUGHT.md §12 - short version: /usr/bin/chromium
# on Ubuntu is a snap, snap's home interface can't touch dot-prefixed
# paths like the ~/.local/share/ARKtube one above, and a snap-confined
# Chrome handed that path fails by GPU-process-crash-looping rather than
# with a clean permission error.
if [ "${EMBED_SUPPORTED}" = "1" ]; then
    CHROME_REAL_BIN="$(command -v "${CHROME_CANDIDATE}" 2>/dev/null || true)"
    CHROME_REAL_BIN="$(readlink -f "${CHROME_REAL_BIN}" 2>/dev/null || echo "${CHROME_REAL_BIN}")"
    case "${CHROME_REAL_BIN}" in
        /snap/*)
            SNAP_SLUG="$(echo "${CHROME_REAL_BIN}" | cut -d/ -f3)"
            SNAP_COMMON="${HOME}/snap/${SNAP_SLUG}/common"
            mkdir -p "${SNAP_COMMON}" 2>/dev/null || true
            echo "ARKtube: ${CHROME_CANDIDATE} resolves to a snap" \
                 "(${CHROME_REAL_BIN}) - using" \
                 "${SNAP_COMMON}/arktube-chromedata as its profile dir" \
                 "instead of ${CHROME_PROFILE_DIR}, which snap confinement" \
                 "can't write to (see docs/bugs-caught/BUGS-CAUGHT.md §12)." >&2
            CHROME_PROFILE_DIR="${SNAP_COMMON}/arktube-chromedata"
            CHROME_LOCK_PATTERN="--user-data-dir=${CHROME_PROFILE_DIR}"
            # Re-run cleanup now that the path is corrected, so a lock
            # left behind by a previous corrected-path run is still
            # cleared (the proactive call above ran against the
            # pre-redirect path). Cheap and idempotent either way.
            reap_stale_chrome
            ;;
    esac
fi

NEUTRALINO_EXTRA_ARGS=()
NEUTRALINO_ENV=()
if [ "${EMBED_SUPPORTED}" = "0" ]; then
    echo "ARKtube: hybrid Chrome embed unavailable (no X11 display/Xwayland," \
         "or missing xdotool/wmctrl/xbindkeys/Chrome) - loading YouTube" \
         "directly in ARKtube's own webview instead." >&2
    NEUTRALINO_EXTRA_ARGS+=(--native-fallback)
else
    # Puts Neutralino's own GTK/WebKitGTK window on the same Xwayland
    # display embed-chrome.sh is about to force Chrome onto
    # (--ozone-platform=x11 there) - see that script's header for why
    # both halves need to land on the same X11 display for xdotool to
    # find and reparent them. No-op on a real X11 session.
    NEUTRALINO_ENV+=(GDK_BACKEND=x11)
fi

env "${NEUTRALINO_ENV[@]}" /usr/lib/arktube/ARKtube --path="${ARKTUBE_DATA_DIR}" "${NEUTRALINO_EXTRA_ARGS[@]}" "$@" &
NEUTRALINO_PID=$!

EMBED_PID=""
if [ "${EMBED_SUPPORTED}" = "1" ]; then
    sleep 1
    /usr/lib/arktube/embed-chrome.sh "ARKtube" "https://www.youtube.com/tv#/" \
        "${CHROME_PROFILE_DIR}" "${IMMERSIVE_ARG}" &
    EMBED_PID=$!
fi

if [ -n "${EMBED_PID}" ]; then
    while kill -0 "${NEUTRALINO_PID}" 2>/dev/null; do
        if ! kill -0 "${EMBED_PID}" 2>/dev/null && ! pgrep -f -- "${CHROME_LOCK_PATTERN}" >/dev/null 2>&1; then
            kill -TERM "${NEUTRALINO_PID}" 2>/dev/null || true
            break
        fi
        sleep 1
    done
    kill -TERM "${EMBED_PID}" 2>/dev/null || true
fi

wait "${NEUTRALINO_PID}" 2>/dev/null || true
LAUNCHER
chmod 755 "${PKGROOT}/usr/bin/arktube"

sed 's/Exec=AppRun/Exec=arktube/; s/Icon=appIcon/Icon=arktube/' \
    "${PACKAGING_DIR}/ARKtube.desktop" \
    > "${PKGROOT}/usr/share/applications/arktube.desktop"

echo "==> Writing DEBIAN/control"
INSTALLED_SIZE_KB="$(du -sk "${PKGROOT}/usr" | cut -f1)"
cat > "${PKGROOT}/DEBIAN/control" <<CONTROL
Package: ${PKG_NAME}
Version: ${VERSION}
Section: video
Priority: optional
Architecture: ${ARCH}
Installed-Size: ${INSTALLED_SIZE_KB}
Depends: libwebkit2gtk-4.1-0
Recommends: xdotool, wmctrl, xbindkeys, xwayland
Maintainer: Horizon ARK Studio
Description: YouTube, as a desktop app.
 A lightweight YouTube desktop client built with Neutralinojs.
 It looks like YouTube and behaves like an app - no redesign,
 no replacement frontend, just YouTube in a proper desktop window.
CONTROL

echo "==> Building .deb"
dpkg-deb --build --root-owner-group "${PKGROOT}" "${OUTPUT}"

echo "==> Done: ${OUTPUT}"
