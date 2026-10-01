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

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, installed as `net.die.phoneapi.dev` so it can sit next to a release build.

The same checks CI runs:

```sh
./gradlew spotlessCheck detektMain lint testDebugUnitTest :api-model:test assembleDebug --continue
```

## Install

`scripts/dev-setup.sh` builds the debug APK, installs it, grants `WRITE_SECURE_SETTINGS`, enables the accessibility service, mints a token into `.dev/pairing.json` with `scripts/pair-adb.sh`, and starts the helper.

```sh
scripts/dev-setup.sh --bind-all
```

`--bind-all` makes the server listen on every interface, including loopback, and forwards the API port, so an emulator is reachable at `127.0.0.1`. Without it the server binds only to the Wi-Fi or Ethernet address, and `.dev/pairing.json` points there. `--no-build` skips Gradle when the APK is already built.

The bearer token, host, port, and certificate pins are in `.dev/pairing.json`, which is gitignored.

To do the same steps by hand:

```sh
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant net.die.phoneapi.dev android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure enabled_accessibility_services \
  net.die.phoneapi.dev/net.die.phoneapi.a11y.PhoneAccessibilityService
adb shell settings put secure accessibility_enabled 1
scripts/helper-start.sh
```

Open PhoneAPI and turn the accessibility service on if the settings write does not stick. The in-app screen explains each permission, shows status, pairs computers, and lists them for revoking. On Android 13 and later, an APK installed from a browser or file manager has the accessibility toggle blocked as a restricted setting until you choose "Allow restricted settings" in the app's App info menu.

## Pairing

A paired computer holds its own bearer token. There are two ways to get one.

**From a browser, no ADB.** In the app, tap "Pair a computer". The phone shows an address such as `https://Android_1A2B3C4D.local:41234/pair` (or the IP address; there is a toggle) and keeps pairing open for five minutes, or until you leave the screen. Open the address on a computer on the same network, accept the self-signed certificate warning, enter a name, and tap Allow on the phone. The page then shows the base URL, the token, an MCP config snippet, a pinned `curl` example, and a `pairing.json` download.

Android chooses the `.local` name, not the app, and only reports it on Android 16 with a recent connectivity module; otherwise the phone shows the IP address. The name can change, for example after a reboot, so treat it as a convenience for typing the pairing address.

The pairing routes (`GET /pair`, `POST /v1/pair`, `GET /v1/pair/{id}`) are the only ones that run without a token, and only while the window is open. Otherwise they get the same empty 404 as any unauthenticated request. One request can wait for approval at a time. An approved token is handed out once, and is revoked if nobody collects it within a minute.

This assumes a trusted local network for the few minutes pairing is open: the window is off by default, short, and needs an explicit Allow on the phone. There is no code to compare, because a browser cannot check the certificate it was shown. After pairing, clients pin the certificate, so later connections are protected the way SSH's first-use trust is.

**Over ADB.** If you installed the APK with ADB, the shell user is already trusted, so no approval is needed. Either run the broadcast directly, which prints `Broadcast completed: result=0, data="<pairing JSON>"`:

```sh
adb shell am broadcast -a net.die.phoneapi.CREATE_TOKEN \
  -n net.die.phoneapi/net.die.phoneapi.ShellCommandReceiver --es name laptop
```

or use `scripts/pair-adb.sh`, which needs only `adb` and prints just the JSON:

```sh
scripts/pair-adb.sh --name laptop > pairing.json
scripts/pair-adb.sh --forward > pairing.json   # emulator, or no reachable Wi-Fi address
```

The pairing JSON is the same from both paths:

```json
{"host":"192.168.1.23","port":41234,"certSha256":"09:44:…","spkiSha256":"q5Z…=","token":"pa_…","name":"laptop"}
```

`host` is the address the client reached: the Wi-Fi or Ethernet address for ADB (empty if there is none), or whatever the browser used. `--forward` rewrites it to `127.0.0.1`.

## Authentication

Every request needs `Authorization: Bearer <token>`. A missing or unknown token gets an empty 404. The `access_token` query parameter is accepted only on `GET /viewer` and on WebSocket upgrades, which is how the viewer page opens its video and audio sockets. Every other route ignores it and still requires the header. The certificate is self-signed; pin `certSha256` (certificate hash) or `spkiSha256` (public-key hash, the form `curl --pinnedpubkey sha256//…` takes) from the pairing JSON.

Tokens carry scopes: `observe`, `control`, `browser`, `stream`, and `admin`. Pairing and `CREATE_TOKEN` grant all of them. Create a narrower token with `POST /v1/tokens`.

`scripts/papi` reads `.dev/pairing.json`, or the file named by `PAPI_PAIRING`, and skips certificate verification, which is appropriate for development:

```sh
scripts/papi GET /v1/device
scripts/papi POST /v1/input/tap '{"x":540,"y":1200,"humanize":false}'
scripts/papi WS /v1/events
```

`WS` prints text frames for `PAPI_WS_SECONDS` (default 10). HTTP calls print the body and then the status code.

## Helper

The helper is a separate `app_process` that registers a binder with the app. `/v1/device` reports it as `running`, `starting`, `needs_pairing`, `needs_usb`, or `stopped`. `capabilities` shrinks to what works in the current state.

`stream.video.projection` and `stream.audio.playbackCapture` stay in the map and are always `false`: this build has no MediaProjection or AudioPlaybackCapture path. Video uses the helper display mirror (`stream.video.mirror`). Audio uses the helper's remote submix (`stream.audio.submix`, Android 11+). The other flags follow the code that implements them: accessibility snapshots and gestures, helper injection, the Android 13 input method, helper key events, accessibility and helper screenshots, stable node ids on Android 13+, helper app management, logcat, and Chrome DevTools, low-latency encoder requests and Wireless Debugging on Android 11+, and `WRITE_SECURE_SETTINGS`.

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

The process detaches, so the command returns while the helper keeps running. That USB start does not survive a reboot; run it again, or use one of the on-phone paths. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash, a package update, or a reboot (once the user has unlocked). It turns Wireless Debugging on, starts the helper, and turns Wireless Debugging off again if it was off. Granting `WRITE_SECURE_SETTINGS`, which `dev-setup.sh` does, is what allows that. If Shizuku is installed and running, the app starts the helper through it instead, including after a reboot.

To pair from the phone (Android 11+), tap "Pair wireless debugging" in the app. It opens Developer options and watches mDNS for this phone's pairing service. When the system "Pair device with pairing code" dialog opens, a heads-up notification asks for the code inline. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when the user switches apps. Wireless debugging is hidden on Android 10. If Shizuku is installed, the app offers "Start with Shizuku" first. The USB command stays available unless wireless debugging is already on.

These calls need the helper: injected input (`input.inject`), `POST /v1/apps/{pkg}/stop` and `/clear`, helper screenshots, logcat, every `/v1/browser` route, and the mirror and audio-submix stream paths. Snapshots, accessibility gestures, app launch, and wake still work while it is down.

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

Point an MCP client at `https://<host>:<port>/mcp` with the same bearer token. `access_token` is not accepted on `/mcp`. Many MCP clients verify certificates and will reject the self-signed one unless they offer a way to trust or pin it. The server is stateless Streamable HTTP. `tools/list` includes `device_info`, `ui_snapshot`, input and app tools, `browser_*`, `screenshot`, and `logcat_tail`. A tool is omitted when the token lacks its scope or the capability is false. Each tool's input schema is generated from the Kotlin type that tool decodes, so fields such as `tap.count` cannot drift out of the schema. When a tool is still available through a weaker backend (accessibility gestures without helper injection, or node ids that are not stable), its description says so. Resources: `phoneapi://device/capabilities` and `phoneapi://viewer`.

`GET /v1/openapi.json` (observe scope) is an OpenAPI 3.1 document generated from the same types. Bearer auth is the `bearer` security scheme, and each operation has `x-scope`. WebSocket routes are marked with `x-websocket`.

Closed request fields are enums. A value outside the set is a `400` `bad_request` on REST and a tool error on MCP. Matching is case-insensitive for node state, swipe direction, log level, and node action.

| Field | Values |
| --- | --- |
| Wait node `state` | `present`, `absent`, `visible`, `enabled`, `disabled`, `checked`, `unchecked`, `focused`, `selected` |
| Browser element `by` | `css`, `xpath`, `text` |
| Browser element `state` | `present`, `absent`, `visible` |
| Browser log `level` | `verbose`, `info`, `warning`, `error` |
| Swipe `direction` | `up`, `down`, `left`, `right` |
| Node `action` | The accessibility catalog: `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`, `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`, and the other standard names (`scrollUp`, `copy`, `paste`, …). App-defined action labels are rejected. |

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
| `POST` | `/v1/ime/hide` | control |
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
| `GET` | `/pair` | none, only while pairing is open |
| `POST` | `/v1/pair` | none, only while pairing is open |
| `GET` | `/v1/pair/{id}` | none, the request id |
| `GET`, `POST` | `/v1/tokens` | admin |
| `DELETE` | `/v1/tokens/{id}` | admin |
| `POST` | `/mcp` | the tool's scope |

`GET /v1/device` is the capability check: `helper`, `accessibilityConnected`, and `capabilities` say what the current device can do. Errors are JSON (`error`, `message`, and sometimes `state`) with an HTTP status. A missing helper is `503` `helper_unavailable`.
