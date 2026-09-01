#!/usr/bin/env bash
#
# build-dmg.sh - reproducible ARKtube macOS .app + .dmg build.
#
# Mirrors packaging/linux/build-appimage.sh: builds with
# `neu build --embed-resources` (see docs/BUGS-CAUGHT.md) so resources
# are baked straight into the platform binary, then lays that binary out
# as a standard ARKtube.app bundle and packs it into a .dmg with hdiutil
# (present on every macOS runner/host, no extra tooling required).
#
# Usage:
#   cd ARKtube/
#   neu update                        # pull the pinned 6.8.0 binaries
#   ./packaging/macos/build-dmg.sh
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION="$(grep -o '"version": *"[^"]*"' "${ROOT_DIR}/neutralino.config.json" | head -1 | sed 's/.*"version": *"\(.*\)"/\1/')"
BUILD_DIR="${ROOT_DIR}/.dmg-build"
APP_DIR="${BUILD_DIR}/ARKtube.app"
DMG_STAGING="${BUILD_DIR}/dmg-root"
OUTPUT="${ROOT_DIR}/ARKtube-${VERSION}-macos.dmg"

echo "==> Cleaning previous build directory"
rm -rf "${BUILD_DIR}"
mkdir -p "${APP_DIR}/Contents/MacOS" "${APP_DIR}/Contents/Resources" "${DMG_STAGING}"

echo "==> Building with neu (embedding resources into the binary)"
cd "${ROOT_DIR}"
neu build --embed-resources

# Universal binary first, so one .app works on both Apple Silicon and
# Intel Macs without picking an arch at build time; fall back to the
# arch-specific build if this Neutralino version doesn't ship one.
BIN_SRC="${ROOT_DIR}/dist/ARKtube/ARKtube-mac_universal"
if [[ ! -f "${BIN_SRC}" ]]; then
    BIN_SRC="${ROOT_DIR}/dist/ARKtube/ARKtube-mac_x64"
fi
if [[ ! -f "${BIN_SRC}" ]]; then
    echo "error: expected build output not found under ${ROOT_DIR}/dist/ARKtube/" >&2
    echo "       (check 'neu build' output above for the actual path)" >&2
    exit 1
fi

echo "==> Assembling ARKtube.app"
install -m755 "${BIN_SRC}" "${APP_DIR}/Contents/MacOS/ARKtube"

# NL_PATH needs to point somewhere writable at runtime, same reasoning
# as packaging/linux/AppRun - a .app bundle under /Applications is not
# writable by a normal user, so launch through a thin wrapper instead of
# calling the binary directly.
mv "${APP_DIR}/Contents/MacOS/ARKtube" "${APP_DIR}/Contents/MacOS/ARKtube-bin"
cat > "${APP_DIR}/Contents/MacOS/ARKtube" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ARKTUBE_DATA_DIR="${HOME}/Library/Application Support/ARKtube"
mkdir -p "${ARKTUBE_DATA_DIR}"
exec "${HERE}/ARKtube-bin" --path="${ARKTUBE_DATA_DIR}"
LAUNCHER
chmod 755 "${APP_DIR}/Contents/MacOS/ARKtube"

if command -v sips >/dev/null 2>&1 && command -v iconutil >/dev/null 2>&1; then
    echo "==> Building .icns from appIcon.png"
    ICONSET="${BUILD_DIR}/appIcon.iconset"
    mkdir -p "${ICONSET}"
    for size in 16 32 64 128 256 512; do
        sips -z "${size}" "${size}" "${ROOT_DIR}/resources/icons/appIcon.png" \
            --out "${ICONSET}/icon_${size}x${size}.png" >/dev/null
        double=$((size * 2))
        sips -z "${double}" "${double}" "${ROOT_DIR}/resources/icons/appIcon.png" \
            --out "${ICONSET}/icon_${size}x${size}@2x.png" >/dev/null
    done
    iconutil -c icns "${ICONSET}" -o "${APP_DIR}/Contents/Resources/appIcon.icns"
else
    echo "==> sips/iconutil not found, shipping appIcon.png as-is (no .icns)"
    cp "${ROOT_DIR}/resources/icons/appIcon.png" "${APP_DIR}/Contents/Resources/appIcon.png"
fi

cat > "${APP_DIR}/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>ARKtube</string>
    <key>CFBundleDisplayName</key>
    <string>ARKtube</string>
    <key>CFBundleIdentifier</key>
    <string>com.arktube.app</string>
    <key>CFBundleVersion</key>
    <string>${VERSION}</string>
    <key>CFBundleShortVersionString</key>
    <string>${VERSION}</string>
    <key>CFBundlePackageType</key>
    <string>APPL</string>
    <key>CFBundleExecutable</key>
    <string>ARKtube</string>
    <key>CFBundleIconFile</key>
    <string>appIcon</string>
    <key>NSHighResolutionCapable</key>
    <true/>
</dict>
</plist>
PLIST

echo "==> Assembling .dmg staging root"
cp -R "${APP_DIR}" "${DMG_STAGING}/ARKtube.app"
ln -s /Applications "${DMG_STAGING}/Applications"

echo "==> Building .dmg"
rm -f "${OUTPUT}"
hdiutil create -volname "ARKtube" -srcfolder "${DMG_STAGING}" -ov -format UDZO "${OUTPUT}"

echo "==> Done: ${OUTPUT}"
