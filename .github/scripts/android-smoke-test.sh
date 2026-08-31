#!/usr/bin/env bash
# Installs the debug APK on the emulator started by
# reactivecircus/android-emulator-runner, launches MainActivity, and
# fails the job if the process isn't still running a few seconds
# later (i.e. it crashed on startup).
#
# This lives in its own file rather than inline in
# android-build.yml's `script:` block because that action runs each
# line of a multi-line `script:` input as its own separate `sh -c`
# invocation instead of as one combined script -- so `APK=...` set on
# one line was gone by the next, and a multi-line `if`/`fi` split
# across lines was a bare, unterminated `if` in its own subshell:
#
#   [command]/usr/bin/sh -c APK="$(find apk -name '*.apk' | head -n 1)"
#   [command]/usr/bin/sh -c if [ -z "$APK" ];then
#   /usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "fi")
#
# Keeping the logic in a real script file and having `script:` call
# `bash .github/scripts/android-smoke-test.sh` (a single line) sidesteps
# that entirely -- there's only one line for the action to run, and
# bash executes the whole file as the single script it actually is.
set -euo pipefail

APK="$(find apk -name '*.apk' | head -n 1)"
if [ -z "$APK" ]; then
  echo "::error::No .apk file found under apk/ -- listing what's actually there:"
  find apk -type f
  exit 1
fi

echo "Installing $APK"
adb install -r "$APK"
adb shell am start -n com.arktube.app/com.arktube.app.MainActivity
sleep 5

if ! adb shell pidof com.arktube.app; then
  echo "::error::App process not found a few seconds after launch -- it likely crashed on startup. See logcat below."
  adb logcat -d "*:E"
  exit 1
fi

echo "App launched and is still running -- smoke test passed."
