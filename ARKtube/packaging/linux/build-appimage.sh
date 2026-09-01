#!/usr/bin/env bash
#
# build-appimage.sh - reproducible ARKtube AppImage build.
#
# See docs/BUGS-CAUGHT.md for the two bugs this script exists to avoid:
#   1. `resources.neu is missing` - fixed by building with
#      `neu build --embed-resources`, so there's no separate resources
#      file that can end up in the wrong place inside the AppImage.
#   2. `Read-only file system [./.tmp]` abort - fixed by shipping the
#      `AppRun` wrapper (packaging/linux/AppRun) as the AppImage
#      entrypoint instead of the raw binary, so NL_PATH always points
#      at a writable directory at runtime.
#
# Usage:
#   cd ARKtube/
#   neu update                      # pull the pinned 6.8.0 binaries
#   ./packaging/linux/build-appimage.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PACKAGING_DIR="${ROOT_DIR}/packaging/linux"
BUILD_DIR="${ROOT_DIR}/.appimage-build"
APPDIR="${BUILD_DIR}/ARKtube.AppDir"
APPIMAGETOOL="${BUILD_DIR}/appimagetool.AppImage"
OUTPUT="${ROOT_DIR}/ARKtube-x86_64.AppImage"

echo "==> Cleaning previous build directory"
rm -rf "${BUILD_DIR}"
mkdir -p "${APPDIR}"

echo "==> Building with neu (embedding resources into the binary)"
cd "${ROOT_DIR}"
neu build --embed-resources

# `neu build --embed-resources` uses Node's postject to bake resources.neu
# straight into the platform binary, so the only artifact that needs to
# ship for Linux is dist/ARKtube/ARKtube-linux_x64 - there is no
# resources.neu left to lose track of inside the AppImage.
BIN_SRC="${ROOT_DIR}/dist/ARKtube/ARKtube-linux_x64"
if [[ ! -f "${BIN_SRC}" ]]; then
    echo "error: expected build output not found at ${BIN_SRC}" >&2
    echo "       (check 'neu build' output above for the actual path)" >&2
    exit 1
fi

echo "==> Assembling AppDir"
install -Dm755 "${BIN_SRC}" "${APPDIR}/ARKtube"
install -Dm755 "${PACKAGING_DIR}/AppRun" "${APPDIR}/AppRun"
install -Dm644 "${PACKAGING_DIR}/ARKtube.desktop" "${APPDIR}/ARKtube.desktop"
install -Dm644 "${ROOT_DIR}/resources/icons/appIcon.png" "${APPDIR}/appIcon.png"

# appimagetool also expects a top-level .DirIcon
cp "${APPDIR}/appIcon.png" "${APPDIR}/.DirIcon"

echo "==> Fetching appimagetool"
if [[ ! -x "${APPIMAGETOOL}" ]]; then
    curl -L -o "${APPIMAGETOOL}" \
        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
    chmod +x "${APPIMAGETOOL}"
fi

echo "==> Building AppImage"
ARCH=x86_64 "${APPIMAGETOOL}" "${APPDIR}" "${OUTPUT}"

echo "==> Done: ${OUTPUT}"
