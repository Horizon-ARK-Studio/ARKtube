# build-exe.ps1 - reproducible ARKtube Windows build.
#
# Mirrors packaging/linux/build-appimage.sh: builds with
# `neu build --embed-resources` (see docs/BUGS-CAUGHT.md) so resources
# are baked straight into ARKtube-win_x64.exe and there's no separate
# resources.neu to lose track of, then zips it up together with
# ARKtube.bat / Launch-ARKtube.ps1 (see packaging/windows/Launch-ARKtube.ps1
# for why a plain launch of ARKtube.exe isn't enough - it also carries
# over the chrome-mode orphaned-process cleanup that
# packaging/linux/AppRun and packaging/macos/build-dmg.sh's launcher do)
# as the downloadable Windows artifact.
#
# Usage:
#   cd ARKtube/
#   neu update                          # pull the pinned 6.8.0 binaries
#   ./packaging/windows/build-exe.ps1
#
$ErrorActionPreference = "Stop"

$RootDir = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Version = (Get-Content (Join-Path $RootDir "neutralino.config.json") | ConvertFrom-Json).version
$Output = Join-Path $RootDir "ARKtube-$Version-windows-x64.zip"
$StageDir = Join-Path $RootDir ".exe-build\ARKtube"

Write-Host "==> Cleaning previous build directory"
if (Test-Path (Join-Path $RootDir ".exe-build")) {
    Remove-Item -Recurse -Force (Join-Path $RootDir ".exe-build")
}
New-Item -ItemType Directory -Force -Path $StageDir | Out-Null

Write-Host "==> Building with neu (embedding resources into the binary)"
Push-Location $RootDir
try {
    neu build --embed-resources
} finally {
    Pop-Location
}

$BinSrc = Join-Path $RootDir "dist\ARKtube\ARKtube-win_x64.exe"
if (-not (Test-Path $BinSrc)) {
    Write-Error "expected build output not found at $BinSrc (check 'neu build' output above for the actual path)"
    exit 1
}

Write-Host "==> Assembling release folder"
Copy-Item $BinSrc (Join-Path $StageDir "ARKtube.exe")
Copy-Item (Join-Path $RootDir "resources\icons\appIcon.png") (Join-Path $StageDir "appIcon.png")
Copy-Item (Join-Path $PSScriptRoot "Launch-ARKtube.ps1") (Join-Path $StageDir "Launch-ARKtube.ps1")
Copy-Item (Join-Path $PSScriptRoot "ARKtube.bat") (Join-Path $StageDir "ARKtube.bat")

Write-Host "==> Zipping"
if (Test-Path $Output) {
    Remove-Item -Force $Output
}
Compress-Archive -Path (Join-Path $StageDir "*") -DestinationPath $Output

Write-Host "==> Done: $Output"
