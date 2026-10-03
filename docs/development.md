# Developing PhoneAPI

This guide is for working on the app from a source checkout. End-user install lives in the [README](../README.md). The HTTP/MCP surface is in [api.md](api.md). Design decisions and system shape are in [architecture.md](architecture.md).

## Prerequisites

- JDK 21 (Temurin works)
- Android SDK with compileSdk 37 (`ANDROID_HOME` must point at the SDK)
- A device or emulator with USB debugging
- `adb`, `jq`, and `curl` on the host
- [`uv`](https://docs.astral.sh/uv/) for `phoneapi-dev papi WS …` WebSocket calls

Typical macOS Homebrew layout:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

## Client: phoneapi-dev

`scripts/phoneapi-dev` sources `scripts/phoneapi` with debug defaults:

| | `phoneapi` (release) | `phoneapi-dev` |
|--|----------------------|----------------|
| Package | `net.die.phoneapi` | `net.die.phoneapi.dev` |
| Pairing file | `~/.config/phoneapi/pairing.json` | `.dev/pairing.json` |
| `setup` | no | yes |

Override with `PHONEAPI_PKG` or `PAPI_PAIRING` if needed.

## First-time device setup

With a USB device (or emulator) attached:

```sh
scripts/phoneapi-dev setup            # build, install, grant, pair, server, helper
scripts/phoneapi-dev setup --no-build # skip the Gradle build
```

That installs the debug APK as `net.die.phoneapi.dev` (so it can sit next to a release build), grants `WRITE_SECURE_SETTINGS`, writes `.dev/pairing.json`, exposes the server on localhost, and starts the helper.

To rebuild by hand:

```sh
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Day-to-day commands

```sh
scripts/phoneapi-dev pair --name laptop   # mint a token into .dev/pairing.json
scripts/phoneapi-dev server               # adb-forward the API to localhost
scripts/phoneapi-dev helper               # start listener + shell helper
scripts/phoneapi-dev papi GET /v1/device
scripts/phoneapi-dev mcp                  # stdio MCP bridge
```

Open the app for screen reading and taps, wireless debugging, and the computers that have access.

## Checks and tests

Same suite CI runs on pull requests:

```sh
./gradlew spotlessCheck detektMain detektTest lint testDebugUnitTest :api-model:test assembleDebug buildHealth --continue
```

Useful pieces on their own:

```sh
./gradlew spotlessApply    # format Kotlin / Gradle Kotlin DSL
./gradlew spotlessCheck
./gradlew detektMain detektTest
./gradlew lint
./gradlew testDebugUnitTest :api-model:test
```

Release tags also build a signed release APK (`assembleRelease`) after those checks.

## Emulator notes

On a debuggable emulator image, Chrome only publishes `@chrome_devtools_remote` after it is started with remote debugging, for example a `/data/local/tmp/chrome-command-line` file containing `chrome --disable-fre --no-first-run --remote-debugging-port=9222`, followed by a force-stop and launch. Some emulator images also refuse the shell user; `adb root` makes the helper uid 0, which Chrome accepts. `scripts/phoneapi-dev helper` does not change that.
