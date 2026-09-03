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
install -Dm644 "${ROOT_DIR}/resources/icons/appIcon.png" \
    "${PKGROOT}/usr/share/icons/hicolor/512x512/apps/arktube.png"

# A thin launcher in usr/bin, rather than symlinking straight to the
# binary, so NL_PATH can be pointed at a writable per-user data dir the
# same way packaging/linux/AppRun does for the AppImage build - a .deb
# install lands under /usr, which is read-only for the running user.
#
# It also carries over AppRun's chrome-mode cleanup (see AppRun and
# docs/BUGS-CAUGHT.md): Neutralino's chrome mode launches Chrome/Chromium
# as a fully-detached child process (TinyProcessLib calls setpgid(0, 0)
# on Linux), so it is NOT part of this launcher's or the Neutralino
# binary's process group. app.exit()/app.killProcess() only ever signal
# the Neutralino server's own PID, never Chrome's - so on an unclean
# exit (Ctrl-C, a window-manager force-quit, a crash) Chrome and its
# profile lock (SingletonLock/SingletonSocket/SingletonCookie under
# .tmp/chromedata) are orphaned, and the next launch hits Chrome's own
# singleton-instance check and silently hands off to that orphan instead
# of starting fresh ("Opening in existing browser session."), which also
# means that window is never wired to app-init.js's close handling.
# Without this, every .deb launch (not just the AppImage) leaks a Chrome
# process on any exit that isn't a clean quit through the app UI.
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
# resources/js/app-init.js's on-screen Immersive Mode button persists its
# on/off state via Neutralino.storage, which is just a plain file at
# ${ARKTUBE_DATA_DIR}/.storage/<key>.neustorage (see api/storage/storage.cpp
# upstream) -- not shell-executed, not youtube.com's own localStorage.
# That button's script runs inside the Chrome child, on youtube.com/tv's
# own origin, a page this app doesn't control, so it is deliberately NOT
# allowlisted to relaunch itself with new Chrome flags (that would mean
# giving that untrusted page's own script exec capability -- see
# neutralino.config.json's nativeAllowList, which stays narrow on
# purpose). This launcher is the trusted side of that split instead: it
# reads the same persisted file directly, off disk, and decides on
# ARKtube's behalf which --chrome-args Neutralino should hand to Chrome
# for *this* launch. Real hardening can only take effect at Chrome's own
# process start (see chrome.cpp, which bakes `args` into the command line
# it spawns once and never revisits) -- not mid-session, which is exactly
# why this lives here and not in app-init.js.
IMMERSIVE_FLAG_FILE="${ARKTUBE_DATA_DIR}/.storage/immersiveMode.neustorage"
BASE_CHROME_USER_AGENT='--user-agent="Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible;"'

EXTRA_ARGS=()
if [ -f "${IMMERSIVE_FLAG_FILE}" ] && [ "$(cat "${IMMERSIVE_FLAG_FILE}" 2>/dev/null)" = "1" ]; then
    # --kiosk drops whatever chrome UI --app= mode still leaves reachable
    # (window controls, menu entry points) and forces fullscreen at the
    # browser level itself -- stronger than the DOM/Neutralino fullscreen
    # call app-init.js also makes, which only asks the page for
    # fullscreen and can't touch devtools either way. --disable-dev-tools
    # is the actual flag that closes off F12 / Ctrl+Shift+I / right-click
    # Inspect / chrome://inspect; the in-page keydown/contextmenu guards
    # in app-init.js are a same-session stand-in for the gap between
    # "button clicked" and "next relaunch", not a substitute for this.
    EXTRA_ARGS+=(--chrome-args="${BASE_CHROME_USER_AGENT} --kiosk --disable-dev-tools --disable-pinch --overscroll-history-navigation=0")
fi

/usr/lib/arktube/ARKtube --path="${ARKTUBE_DATA_DIR}" "${EXTRA_ARGS[@]}" "$@"
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
Maintainer: Horizon ARK Studio
Description: YouTube, as a desktop app.
 A lightweight YouTube desktop client built with Neutralinojs.
 It looks like YouTube and behaves like an app - no redesign,
 no replacement frontend, just YouTube in a proper desktop window.
CONTROL

echo "==> Building .deb"
dpkg-deb --build --root-owner-group "${PKGROOT}" "${OUTPUT}"

echo "==> Done: ${OUTPUT}"
