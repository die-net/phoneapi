#!/usr/bin/env bash
# Installs the debug APK on the connected device/emulator, enables the accessibility service,
# and mints a token over ADB with scripts/pair-adb.sh. Writes .dev/pairing.json.
#
#   scripts/dev-setup.sh [--no-build] [--bind-all]
#
# --bind-all makes the server also listen on loopback and forwards the API port to localhost
# (needed for emulators, whose LAN address isn't reachable from the host).
set -euo pipefail
cd "$(dirname "$0")/.."

PKG="${PHONEAPI_PKG:-net.die.phoneapi}"
SERVICE="$PKG/net.die.phoneapi.a11y.PhoneAccessibilityService"
build=1
bind_all=0
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
    --bind-all) bind_all=1 ;;
    *) echo "unknown arg $arg" >&2; exit 2 ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

if [[ $build == 1 ]]; then
  ./gradlew assembleDebug -q --console=plain
fi

adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; do sleep 1; done
adb install -r -g app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS

# Enabling right after install can race the package scan and get cleared; retry until bound.
# Append to the existing colon-separated list so other services stay enabled.
for _ in $(seq 1 10); do
  current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
  if [[ -z "$current" || "$current" == "null" ]]; then
    desired="$SERVICE"
  elif [[ ":$current:" == *":$SERVICE:"* ]]; then
    desired="$current"
  else
    desired="$current:$SERVICE"
  fi
  adb shell settings put secure enabled_accessibility_services "$desired"
  adb shell settings put secure accessibility_enabled 1
  sleep 1
  if adb shell dumpsys accessibility | grep -q "Bound services:{Service\[label=PhoneAPI"; then break; fi
done

mkdir -p .dev
pair_args=(--name dev-setup)
if [[ $bind_all == 1 ]]; then pair_args+=(--forward); fi
PHONEAPI_PKG="$PKG" scripts/pair-adb.sh "${pair_args[@]}" > .dev/pairing.json

# Shell helper: input injection, screencap, and privileged app commands. It detaches and returns.
scripts/helper-start.sh "$PKG" || echo "Helper did not start; the app keeps working without it" >&2
echo "Ready (token in .dev/pairing.json; use scripts/papi)"
