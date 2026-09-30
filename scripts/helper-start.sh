#!/usr/bin/env bash
# Starts the shell-UID helper on a device that is already reachable with adb, then returns.
# The helper detaches from this session, so it keeps running after adb exits.
#
#   scripts/helper-start.sh [package]
set -euo pipefail
PKG="${1:-${PHONEAPI_PKG:-net.die.phoneapi}}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb shell "CLASSPATH=\$(pm path $PKG | tr -d '\r' | sed -e s/^package:// | tr '\n' ':') app_process / net.die.phoneapi.helper.Main --pkg $PKG"
echo "Helper start command returned. Check logcat for PhoneApiHelper."
