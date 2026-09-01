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
cat > "${PKGROOT}/usr/bin/arktube" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
DATA_HOME="${XDG_DATA_HOME:-${HOME}/.local/share}"
ARKTUBE_DATA_DIR="${DATA_HOME}/ARKtube"
mkdir -p "${ARKTUBE_DATA_DIR}"
exec /usr/lib/arktube/ARKtube --path="${ARKTUBE_DATA_DIR}" "$@"
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
