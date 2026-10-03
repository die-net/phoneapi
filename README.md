# PhoneAPI

PhoneAPI is an Android app that lets a paired client drive the device and its Chrome tabs. The HTTP and WebSocket API, plus an on-device [MCP](https://modelcontextprotocol.io) server, listen on an abstract local socket named after the package while the listener is running. A computer reaches that socket with `adb forward` (USB or Wireless Debugging).

The shell helper reads the UI through UiAutomation, injects input, captures the screen, and runs privileged app commands. It is started over USB, or on Android 11 or newer over Wireless Debugging. Chrome DevTools uses the same split. On Android 11 and newer adbd opens Chrome's socket, because the helper is not allowed to. On Android 10 the helper connects to an adb reverse socket, and the computer forwards that to Chrome. Video and audio are encoded on the device and played in a browser.

Android 10 and newer (minSdk 29). Wireless Debugging and helper restart without a USB cable need Android 11.

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

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, installed as `net.die.phoneapi.dev` so it can sit next to a release build.

The same checks CI runs:

```sh
./gradlew spotlessCheck detektMain lint testDebugUnitTest :api-model:test assembleDebug --continue
```

## Install

`scripts/dev-setup.sh` builds the debug APK, installs it, grants `WRITE_SECURE_SETTINGS`, mints a token into `.dev/pairing.json` with `scripts/pair-adb.sh`, and starts the helper over USB.

```sh
scripts/dev-setup.sh            # USB device
scripts/dev-setup.sh --no-build # skip the Gradle build
```

`scripts/pair-adb.sh` forwards the abstract socket and writes `scheme` `http` with host `127.0.0.1`, so the client uses `http://127.0.0.1`. The bearer token, host, and port are in `.dev/pairing.json`, which is gitignored.

To do the same steps by hand:

```sh
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant net.die.phoneapi.dev android.permission.WRITE_SECURE_SETTINGS
scripts/pair-adb.sh
scripts/helper-start.sh
```

Open PhoneAPI for status, the USB forward command, the helper start command, and the list of tokens. On Android 11 and newer the same screen can pair Wireless Debugging. You can revoke a token there at any time.

## Pairing

A paired computer holds its own bearer token. Mint one over ADB. The shell user is already trusted, so no approval on the phone is needed. Either run the broadcast directly, which prints `Broadcast completed: result=0, data="<pairing JSON>"`:

```sh
adb shell am broadcast -a net.die.phoneapi.CREATE_TOKEN \
  -n net.die.phoneapi/net.die.phoneapi.ShellCommandReceiver --es name laptop
```

or use `scripts/pair-adb.sh`, which needs only `adb`. It forwards the abstract socket and prints the JSON:

```sh
scripts/pair-adb.sh --name laptop > pairing.json
```

The broadcast returns this shape. `scripts/pair-adb.sh` then inserts `"scheme":"http"` (the host is already `127.0.0.1`):

```json
{"scheme":"http","host":"127.0.0.1","port":41234,"token":"pa_…","name":"laptop"}
```

## Authentication

Every request needs `Authorization: Bearer <token>`. A missing or unknown token gets an empty 404. The `access_token` query parameter is accepted only on `GET /viewer` and on WebSocket upgrades, which is how the viewer page opens its video and audio sockets. Every other route ignores it and still requires the header.

Tokens carry scopes: `observe`, `control`, `browser`, `stream`, and `admin`. `CREATE_TOKEN` grants all of them. Create a narrower token with `POST /v1/tokens`.

`scripts/papi` reads `.dev/pairing.json`, or the file named by `PAPI_PAIRING`:

```sh
scripts/papi GET /v1/device
scripts/papi POST /v1/input/tap '{"x":540,"y":1200,"humanize":false}'
scripts/papi WS /v1/events
```

`WS` prints text frames for `PAPI_WS_SECONDS` (default 10). HTTP calls print the body and then the status code.

## Helper

The helper is a separate `app_process` that registers a binder with the app. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`. `capabilities` shrinks to what works in the current state.

`GET /v1/device` is the capability check. `helper` is the status below, `uiAutomationConnected` means the helper's UiAutomation session is up, and `capabilities` says what the current device can do. Snapshots, injected input, key events, screenshots, app stop and clear, logcat, and Chrome DevTools need the helper. `ui.stableIds` needs Android 13. `encoder.lowLatency`, `adb.wireless`, and `stream.audio.submix` need Android 11. `settings.secure` is `WRITE_SECURE_SETTINGS`. Video is the helper display mirror (`stream.video.mirror`). Touches inject through the helper. Text `mode` is `auto`, `keyboard`, `keyevent`, or `setText`.

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

The process detaches, so the command returns while the helper keeps running. That USB start does not survive a reboot; run it again after reboot, or start the listener and helper over Wireless Debugging. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash or a package update while its process is running. A successful launch leaves Wireless Debugging on, because that connection is also how the browser API reaches Chrome. Granting `WRITE_SECURE_SETTINGS`, which `dev-setup.sh` does, is what allows the app to turn Wireless Debugging on.

On Android 10, browser calls use a USB tunnel instead. `scripts/helper-start.sh` installs it. By hand:

```sh
adb forward tcp:9222 localabstract:chrome_devtools_remote
adb reverse localabstract:phoneapi_cdp tcp:9222
```

To pair Wireless Debugging from the phone (Android 11+), tap "Pair wireless debugging" in the app. It opens Developer options and watches mDNS for this phone's pairing service. When the system "Pair device with pairing code" dialog opens, a heads-up notification asks for the code inline. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when the user switches apps. On Android 10 that row is hidden. The USB command stays available.

These calls need the helper: UI snapshots and finds, injected input, key events, typing, `POST /v1/apps/{pkg}/stop` and `/clear`, screenshots, logcat, every `/v1/browser` route, and the mirror and audio-submix stream paths. App launch and wake still run without it. Wake falls back to a system activity when the helper is down. A launch from a background process needs the helper, because Android blocks the app itself from starting activities then.

## Usage

Read the screen and tap a control:

```sh
scripts/papi GET '/v1/ui/snapshot?format=compact'
scripts/papi POST /v1/ui/find '{"selector":{"text":"Settings"}}'
scripts/papi POST /v1/input/tap '{"selector":{"text":"Settings"}}'
```

Wait until a window is in front, then launch or stop an app:

```sh
scripts/papi POST /v1/wait \
  '{"all":[{"type":"window","package":"com.android.settings"}],"timeoutMs":10000}'
scripts/papi POST /v1/apps/com.android.settings/launch
scripts/papi POST /v1/apps/com.android.settings/stop
```

A `browser.*` condition also needs the browser scope, on REST and on the MCP `wait_for` tool. An observe-only token is `403 forbidden` for that wait. Node, window, and the other non-browser conditions stay on the observe scope.

Drive Chrome. The helper must be running, and Chrome must be running with USB debugging on so it publishes `@chrome_devtools_remote`. On Android 11 and newer PhoneAPI also has to be paired with Wireless Debugging. On Android 10 the USB tunnel from the helper section has to be up. A target id looks like `chrome_devtools_remote~<page id>`.

```sh
scripts/papi GET /v1/browser/targets
scripts/papi POST /v1/browser/tabs '{"url":"https://example.com/"}'
scripts/papi POST /v1/browser/targets/TARGET/navigate '{"url":"https://example.org/"}'
scripts/papi GET /v1/browser/targets/TARGET/snapshot
scripts/papi POST /v1/browser/targets/TARGET/tap '{"selector":"a"}'
scripts/papi POST /v1/browser/targets/TARGET/evaluate '{"expression":"document.title"}'
```

On a debuggable emulator image, Chrome only publishes that socket after it is started with remote debugging, for example a `/data/local/tmp/chrome-command-line` file containing `chrome --disable-fre --no-first-run --remote-debugging-port=9222`, followed by a force-stop and launch. Some emulator images also refuse the shell user; `adb root` makes the helper uid 0, which Chrome accepts. `scripts/helper-start.sh` does not change that.

Open the viewer at `http://127.0.0.1:<port>/viewer` with the stream scope (after `adb forward`). It plays `WS /v1/stream/video` and `WS /v1/stream/audio` with WebCodecs.

Point an MCP client at `http://127.0.0.1:<port>/mcp` with the same bearer token. `access_token` is not accepted on `/mcp`. The server is stateless Streamable HTTP. It accepts a `Host` of `localhost`, `127.0.0.1`, or `::1`. `scripts/pair-adb.sh` always writes `127.0.0.1`.

`scripts/mcp` is a stdio bridge for MCP clients. It reads the same pairing file as `scripts/papi` (`.dev/pairing.json`, or the file named by `PAPI_PAIRING`) and forwards one JSON-RPC message per line. A notification comes back as HTTP 202 and produces no stdout line. Logs go to stderr.

```sh
scripts/mcp
```

Cursor starts that process itself. Give it an absolute path to the script:

```json
{
  "mcpServers": {
    "phone": {
      "command": "/absolute/path/to/phoneapi/scripts/mcp"
    }
  }
}
```

`tools/list` includes `device_info`, `ui_snapshot`, input and app tools, `browser_*`, `screenshot`, and `logcat_tail`. A tool is omitted when the token lacks its scope or the capability is false, so a stopped helper hides the browser, logcat, screenshot, and UI tools. `device_info` stays listed and reports helper status and capabilities. Each tool's input schema is generated from the Kotlin type that tool decodes, so fields such as `tap.count` cannot drift out of the schema. When node ids are not stable (below Android 13), a tool's description says so. Resources: `phoneapi://device/capabilities` and `phoneapi://viewer`.

`GET /v1/openapi.json` (observe scope) is an OpenAPI 3.1 document generated from the same types. Bearer auth is the `bearer` security scheme, and each operation has `x-scope`. WebSocket routes are marked with `x-websocket`.

Closed request fields are enums. A value outside the set is a `400` `bad_request` on REST and a tool error on MCP. Matching is case-insensitive for node state, swipe direction, log level, and node action.

| Field | Values |
| --- | --- |
| Wait node `state` | `present`, `absent`, `visible`, `enabled`, `disabled`, `checked`, `unchecked`, `focused`, `selected` |
| Browser element `by` | `css`, `xpath`, `text` |
| Browser element `state` | `present`, `absent`, `visible` |
| Browser log `level` | `verbose`, `info`, `warning`, `error` |
| Swipe `direction` | `up`, `down`, `left`, `right` |
| Node `action` | `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`, `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`, and the other standard names (`scrollUp`, `copy`, `paste`, …). An action name the node lists is accepted, including an app-defined label. `imeEnter` needs Android 11. |

## API

| Method | Path | Scope |
| --- | --- | --- |
| `GET` | `/v1/device` | observe |
| `GET` | `/v1/openapi.json` | observe |
| `POST` | `/v1/device/wake`, `/unlock`, `/lock` | control |
| `PUT`, `DELETE` | `/v1/device/pin` | admin |
| `GET` | `/v1/ui/snapshot` | observe |
| `POST` | `/v1/ui/find` | observe |
| `POST` | `/v1/ui/nodes/{ref}/action` | control |
| `GET` | `/v1/screenshot` | observe |
| `POST` | `/v1/input/tap`, `/swipe`, `/gesture`, `/key`, `/text` | control |
| `POST` | `/v1/ime/hide`, `/show` | control |
| `GET` | `/v1/apps` | observe |
| `POST` | `/v1/apps/{pkg}/launch` | control |
| `POST` | `/v1/apps/{pkg}/stop`, `/clear` | control, helper |
| `POST` | `/v1/intents` | control |
| `POST` | `/v1/wait` | observe; `browser.*` also needs browser |
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

Errors are JSON (`error`, `message`, and sometimes `state`) with an HTTP status. A missing helper is `503` `helper_unavailable`, and the message says whether it is starting, needs pairing, needs USB, or stopped.
