#!/usr/bin/env bash
# Starts the listener, then the shell-UID helper, on a device that is already reachable with adb.
# The helper detaches from this session, so it keeps running after adb exits.
#
#   scripts/helper-start.sh [package]
# Defaults to the debug application id (net.die.phoneapi.dev).
set -euo pipefail
PKG="${1:-${PHONEAPI_PKG:-net.die.phoneapi.dev}}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
adb shell am start-foreground-service -n "$PKG/net.die.phoneapi.server.ListenerService" >/dev/null
adb shell "CLASSPATH=\$(pm path $PKG | tr -d '\r' | sed -e s/^package:// | tr '\n' ':') app_process / net.die.phoneapi.helper.Main --pkg $PKG"
# Android 10 has no wireless-debugging port. The helper connects to this reverse socket;
# the forward is what actually opens Chrome. Names match USB_DEVTOOLS_SOCKET / PORT.
sdk="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$sdk" -lt 30 ]]; then
  adb forward tcp:9222 localabstract:chrome_devtools_remote >/dev/null
  adb reverse localabstract:phoneapi_cdp tcp:9222 >/dev/null
fi
echo "Helper start command returned. Check logcat for PhoneApiHelper."
