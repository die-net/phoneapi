# PhoneAPI

PhoneAPI is an Android app that lets a paired client drive the device and its Chrome tabs. It serves a TLS HTTP and WebSocket API, plus an on-device [MCP](https://modelcontextprotocol.io) server, for as long as its accessibility service is connected.

The accessibility service reads the native UI. A shell helper, started over USB, Shizuku, or Wireless Debugging, injects input, captures the screen, runs privileged app commands, and opens Chrome DevTools sockets. Video and audio are encoded on the device and played in a browser.

Android 10 and newer (minSdk 29). Wireless Debugging recovery needs Android 11.

## Requirements

- JDK 21
- Android SDK with compileSdk 37 (AndroidX Core 1.19 needs it). `ANDROID_HOME` must point at the SDK.
- A device or emulator with USB debugging
- `adb`, `jq`, and `curl` on the host. `scripts/papi` uses [`uv`](https://docs.astral.sh/uv/) for WebSocket calls.

## Build

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

The same checks CI runs:

```sh
./gradlew spotlessCheck detektMain lint testDebugUnitTest :api-model:test assembleDebug --continue
```

## Install

`scripts/dev-setup.sh` builds the debug APK, installs it, grants `WRITE_SECURE_SETTINGS`, enables the accessibility service, mints a token into `.dev/pairing.json`, starts the helper, and forwards the API port.

```sh
scripts/dev-setup.sh --bind-all
```

`--bind-all` makes the server listen on every interface, including loopback, so `adb forward` can reach an emulator. Without it the server binds only to the Wi-Fi or Ethernet address. `--no-build` skips Gradle when the APK is already built.

The script prints the local URL. The bearer token, port, and certificate fingerprint are in `.dev/pairing.json`, which is gitignored.

To do the same steps by hand:

```sh
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant net.die.phoneapi android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  net.die.phoneapi/net.die.phoneapi.a11y.PhoneAccessibilityService
adb shell settings put secure accessibility_enabled 1
scripts/helper-start.sh
```

Open PhoneAPI and turn the accessibility service on if the settings write does not stick. The in-app screen shows status, a pairing QR code, and the helper start command.

## Authentication

Every request needs `Authorization: Bearer <token>`. A missing or unknown token gets an empty 404. WebSocket clients that cannot set headers may pass `access_token` as a query parameter. The certificate is self-signed; pin `certSha256` from the pairing file.

Tokens carry scopes: `observe`, `control`, `browser`, `stream`, and `admin`. `CREATE_TOKEN` from ADB grants all of them. Create a narrower token with `POST /v1/tokens`.

`scripts/papi` reads `.dev/pairing.json` and skips certificate verification, which is appropriate for the forwarded debug port:

```sh
scripts/papi GET /v1/device
scripts/papi POST /v1/input/tap '{"x":540,"y":1200,"humanize":false}'
scripts/papi WS /v1/events
```

`WS` prints text frames for `PAPI_WS_SECONDS` (default 10). HTTP calls print the body and then the status code.

## Helper

The helper is a separate `app_process` that registers a binder with the app. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`. `capabilities` shrinks to what works in the current state.

| Status | Meaning |
| --- | --- |
| `running` | The helper is connected. |
| `starting` | A launch is in progress. |
| `needs_pairing` | Android 11+ and no paired Wireless Debugging key yet. |
| `needs_usb` | Android 10. Relaunch from a computer. |
| `stopped` | The automatic restart gave up after repeated failures. |

Launch it once from the computer:

```sh
scripts/helper-start.sh
```

The process detaches, so the command returns while the helper keeps running. On Android 11+, after that first pairing, the app restarts the helper itself after a crash, a package update, or a reboot (once the user has unlocked). It turns Wireless Debugging on, starts the helper, and turns Wireless Debugging off again if it was off. Granting `WRITE_SECURE_SETTINGS`, which `dev-setup.sh` does, is what allows that.

To pair from the phone, open the system "Pair device with pairing code" dialog and enter the code in the PhoneAPI notification. The dialog has to stay in front; the pairing port closes when it is paused. If Shizuku is installed, the in-app "Start with Shizuku" button binds a user service instead.

These calls need the helper: injected input (`input.inject`), `POST /v1/apps/{pkg}/stop` and `/clear`, helper screenshots, logcat, every `/v1/browser` route, and the mirror and audio-submix stream paths. Snapshots, accessibility gestures, app launch, and wake still work while it is down.

## Usage

Read the screen and tap a control:

```sh
scripts/papi GET '/v1/ui/snapshot?format=compact'
scripts/papi POST /v1/ui/find '{"text":"Settings"}'
scripts/papi POST /v1/input/tap '{"selector":{"text":"Settings"}}'
```

Wait until a window is in front, then launch or stop an app:

```sh
scripts/papi POST /v1/wait \
  '{"conditions":[{"type":"window","package":"com.android.settings"}],"timeoutMs":10000}'
scripts/papi POST /v1/apps/com.android.settings/launch
scripts/papi POST /v1/apps/com.android.settings/stop
```

Drive Chrome. The helper must be running, and Chrome must be publishing `@chrome_devtools_remote`. A target id looks like `chrome_devtools_remote~<page id>`.

```sh
scripts/papi GET /v1/browser/targets
scripts/papi POST /v1/browser/tabs '{"url":"https://example.com/"}'
scripts/papi POST /v1/browser/targets/TARGET/navigate '{"url":"https://example.org/"}'
scripts/papi GET /v1/browser/targets/TARGET/snapshot
scripts/papi POST /v1/browser/targets/TARGET/tap '{"selector":"a"}'
scripts/papi POST /v1/browser/targets/TARGET/evaluate '{"expression":"document.title"}'
```

On a debuggable emulator image, Chrome only publishes that socket after it is started with remote debugging, for example a `/data/local/tmp/chrome-command-line` file containing `chrome --disable-fre --no-first-run --remote-debugging-port=9222`, followed by a force-stop and launch. Some emulator images also refuse the shell user; `adb root` makes the helper uid 0, which Chrome accepts. `scripts/helper-start.sh` does not change that.

Open the viewer at `https://<host>:<port>/viewer` with the stream scope. It plays `WS /v1/stream/video` and `WS /v1/stream/audio` with WebCodecs. The certificate warning is the self-signed dev certificate.

Point an MCP client at `https://<host>:<port>/mcp` with the same bearer token. The server is stateless Streamable HTTP. `tools/list` includes `device_info`, `ui_snapshot`, input and app tools, `browser_*`, `screenshot`, and `logcat_tail`. A tool is omitted when the token lacks its scope or the capability is false. Resources: `phoneapi://device/capabilities` and `phoneapi://viewer`.

## API

| Method | Path | Scope |
| --- | --- | --- |
| `GET` | `/v1/device` | observe |
| `POST` | `/v1/device/wake`, `/unlock`, `/lock` | control |
| `PUT`, `DELETE` | `/v1/device/pin` | admin |
| `GET` | `/v1/ui/snapshot` | observe |
| `POST` | `/v1/ui/find` | observe |
| `POST` | `/v1/ui/nodes/{ref}/action` | control |
| `GET` | `/v1/screenshot` | observe |
| `POST` | `/v1/input/tap`, `/swipe`, `/gesture`, `/key`, `/text` | control |
| `POST` | `/v1/ime/hide` | control |
| `GET` | `/v1/apps` | observe |
| `POST` | `/v1/apps/{pkg}/launch` | control |
| `POST` | `/v1/apps/{pkg}/stop`, `/clear` | control, helper |
| `POST` | `/v1/intents` | control |
| `POST` | `/v1/wait` | observe |
| `GET` | `/v1/browser/targets` | browser, helper |
| `POST` | `/v1/browser/tabs` | browser, helper |
| `POST` | `/v1/browser/targets/{id}/navigate`, `/tap`, `/evaluate`, `/console` | browser, helper |
| `GET` | `/v1/browser/targets/{id}/snapshot` | browser, helper |
| `WS` | `/v1/browser/targets/{id}/cdp` | browser, helper |
| `WS` | `/v1/events` | observe |
| `GET` | `/viewer` | stream |
| `WS` | `/v1/stream/video`, `/v1/stream/audio` | stream |
| `GET`, `POST` | `/v1/tokens` | admin |
| `DELETE` | `/v1/tokens/{id}` | admin |
| `POST` | `/mcp` | the tool's scope |

`GET /v1/device` is the capability check: `helper`, `accessibilityConnected`, and `capabilities` say what the current device can do. Errors are JSON (`error`, `message`, and sometimes `state`) with an HTTP status. A missing helper is `503` `helper_unavailable`.
