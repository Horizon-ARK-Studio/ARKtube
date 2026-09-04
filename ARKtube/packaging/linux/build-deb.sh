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
# mode and owns the real top-level window; /usr/lib/arktube/embed-chrome.sh
# spawns a real Chrome process and reparents it inside that window (see
# that script for the X11 reparenting + global F11/Escape/Home hotkey
# details). Chrome is a direct child of this launcher rather than a
# fully-detached process Neutralino itself owns, so the close-button
# lifecycle coupling below only needs to watch, not un-orphan, it.
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

/usr/lib/arktube/ARKtube --path="${ARKTUBE_DATA_DIR}" "$@" &
NEUTRALINO_PID=$!

sleep 1
/usr/lib/arktube/embed-chrome.sh "ARKtube" "https://www.youtube.com/tv#/" \
    "${CHROME_PROFILE_DIR}" "${IMMERSIVE_ARG}" &
EMBED_PID=$!

while kill -0 "${NEUTRALINO_PID}" 2>/dev/null; do
    if ! kill -0 "${EMBED_PID}" 2>/dev/null && ! pgrep -f -- "${CHROME_LOCK_PATTERN}" >/dev/null 2>&1; then
        kill -TERM "${NEUTRALINO_PID}" 2>/dev/null || true
        break
    fi
    sleep 1
done

kill -TERM "${EMBED_PID}" 2>/dev/null || true
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
Depends: libwebkit2gtk-4.1-0, xdotool, wmctrl, xbindkeys
Maintainer: Horizon ARK Studio
Description: YouTube, as a desktop app.
 A lightweight YouTube desktop client built with Neutralinojs.
 It looks like YouTube and behaves like an app - no redesign,
 no replacement frontend, just YouTube in a proper desktop window.
CONTROL

echo "==> Building .deb"
dpkg-deb --build --root-owner-group "${PKGROOT}" "${OUTPUT}"

echo "==> Done: ${OUTPUT}"
