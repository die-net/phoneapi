#!/usr/bin/env bash
# Installs the debug APK on the connected device/emulator, enables the accessibility service,
# mints a token over ADB and forwards the API port to localhost. Writes .dev/pairing.json.
#
#   scripts/dev-setup.sh [--no-build] [--bind-all]
#
# --bind-all makes the server also listen on loopback so `adb forward` works (needed for
# emulators, whose LAN address isn't reachable from the host).
set -euo pipefail
cd "$(dirname "$0")/.."

PKG="${PHONEAPI_PKG:-net.die.phoneapi}"
SERVICE="$PKG/net.die.phoneapi.a11y.PhoneAccessibilityService"
RECEIVER="$PKG/net.die.phoneapi.ShellCommandReceiver"
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
for _ in $(seq 1 10); do
  adb shell settings put secure enabled_accessibility_services "$SERVICE"
  adb shell settings put secure accessibility_enabled 1
  sleep 1
  if adb shell dumpsys accessibility | grep -q "Bound services:{Service\[label=PhoneAPI"; then break; fi
done

if [[ $bind_all == 1 ]]; then
  adb shell am broadcast -a net.die.phoneapi.SET_BIND -n "$RECEIVER" --es mode ALL >/dev/null
  sleep 1
fi

mkdir -p .dev
adb shell am broadcast -a net.die.phoneapi.CREATE_TOKEN -n "$RECEIVER" --es name dev-setup \
  | sed -n 's/.*data="\(.*\)"$/\1/p' > .dev/pairing.json

# Shell helper: input injection, screencap, and privileged app commands. It detaches and returns.
scripts/helper-start.sh "$PKG" || echo "Helper did not start; the app keeps working without it" >&2
port="$(jq -r .port .dev/pairing.json)"
adb forward "tcp:$port" "tcp:$port" >/dev/null
echo "Ready: https://127.0.0.1:$port (token in .dev/pairing.json; use scripts/papi)"
