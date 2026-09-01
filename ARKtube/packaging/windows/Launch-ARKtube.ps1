# Launch-ARKtube.ps1 - Windows launch wrapper for ARKtube.exe.
#
# Points Neutralino at a writable per-user data dir (mirrors
# packaging/linux/AppRun and packaging/macos/build-dmg.sh's launcher),
# and carries over the same chrome-mode cleanup those two already do -
# see packaging/linux/AppRun and docs/BUGS-CAUGHT.md for the full
# explanation:
#
# Neutralino's chrome mode launches Chrome/Edge as a fully-detached
# child process, so it is NOT part of this launcher's or the Neutralino
# process's job/process tree, and app.exit()/app.killProcess() only
# ever signal the Neutralino server's own PID, never the browser's. On
# an unclean exit (Alt-F4 outside the app UI, Task Manager "End task"
# on ARKtube.exe alone, a crash) the browser process and its profile
# lock (SingletonLock/SingletonSocket/SingletonCookie under
# .tmp\chromedata) are orphaned, and the next launch hits the browser's
# own singleton-instance check and silently hands off to that orphan
# instead of starting fresh, which also means that window is never
# wired to app-init.js's close handling. Without this, launching
# ARKtube.exe directly (instead of through this wrapper) leaks a
# browser process on any exit that isn't a clean quit through the app UI.
$ErrorActionPreference = "Stop"

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$DataDir = Join-Path $env:LOCALAPPDATA "ARKtube"
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null

$ChromeProfileDir = Join-Path $DataDir ".tmp\chromedata"
$ChromeLockPattern = "--user-data-dir=$ChromeProfileDir"

function Remove-StaleChrome {
    try {
        $stale = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($ChromeLockPattern) }
        foreach ($proc in $stale) {
            Write-Host "ARKtube: cleaning up a leftover browser process from a previous run..."
            Stop-Process -Id $proc.ProcessId -Force -ErrorAction SilentlyContinue
        }
    } catch {
        # Best-effort only - never block launch on cleanup failing.
    }

    foreach ($lockFile in "SingletonLock", "SingletonSocket", "SingletonCookie") {
        $path = Join-Path $ChromeProfileDir $lockFile
        if (Test-Path $path) {
            Remove-Item -Force -ErrorAction SilentlyContinue $path
        }
    }
}

Remove-StaleChrome

try {
    $exe = Join-Path $Here "ARKtube.exe"
    Start-Process -FilePath $exe -ArgumentList "--path=`"$DataDir`"" -Wait
} finally {
    # And clean up after this run however it ends, so the next launch
    # doesn't inherit the problem.
    Remove-StaleChrome
}
