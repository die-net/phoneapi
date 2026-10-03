#!/usr/bin/env bash
# Installs the debug APK on the connected device/emulator and mints a token over ADB with
# scripts/pair-adb.sh. Writes .dev/pairing.json.
#
#   scripts/dev-setup.sh [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG="${PHONEAPI_PKG:-net.die.phoneapi.dev}"
build=1
for arg in "$@"; do
  case "$arg" in
    --no-build) build=0 ;;
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

mkdir -p .dev
PHONEAPI_PKG="$PKG" scripts/pair-adb.sh --name dev-setup > .dev/pairing.json

# Shell helper: input injection, screencap, and privileged app commands. It detaches and returns.
scripts/helper-start.sh "$PKG" || echo "Helper did not start. Snapshots, input, and browser calls need it." >&2
echo "Ready (token in .dev/pairing.json; use scripts/papi)"
