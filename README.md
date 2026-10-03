# PhoneAPI

PhoneAPI is an Android app that lets a paired client drive the device and its Chrome tabs. The HTTP and WebSocket API, plus an on-device [MCP](https://modelcontextprotocol.io) server, listen on an abstract local socket named after the package while the listener is running. A computer reaches that socket with `adb forward` (USB or Wireless Debugging).

The shell helper reads the UI through UiAutomation, injects input, captures the screen, and runs privileged app commands. It is started over USB, or on Android 11 or newer over Wireless Debugging. Chrome DevTools uses the same split. On Android 11 and newer adbd opens Chrome's socket, because the helper is not allowed to. On Android 10 the helper connects to an adb reverse socket, and the computer forwards that to Chrome. Video and audio are encoded on the device and played in a browser.

Android 10 and newer (minSdk 29). Wireless Debugging and helper restart without a USB cable need Android 11.

- HTTP, WebSocket, and MCP surface: [docs/api.md](docs/api.md) (includes OpenAPI)
- How the pieces fit together: [docs/architecture.md](docs/architecture.md)
- Building from source: [docs/development.md](docs/development.md)

## Requirements

- A device or emulator with USB debugging
- `adb`, `jq`, and `curl` on the host
- [`uv`](https://docs.astral.sh/uv/) only if you use `phoneapi papi WS …`

## Install

1. Download a release APK from the project's GitHub Releases page (`phoneapi-v*.apk`).
2. Copy [`scripts/phoneapi`](scripts/phoneapi) somewhere on your `PATH` (for example `~/bin/phoneapi`). It does not need the rest of the repo.
3. Install the APK and allow the privileged grant the app needs for Wireless Debugging:

```sh
adb install -r -g phoneapi-vX.Y.Z.apk
adb shell pm grant net.die.phoneapi android.permission.WRITE_SECURE_SETTINGS
```

4. Pair, expose the server, and start the helper:

```sh
phoneapi pair --name laptop
phoneapi server
phoneapi helper
```

`phoneapi` targets the release package (`net.die.phoneapi`) and stores pairing state in `~/.config/phoneapi/pairing.json` (or `$XDG_CONFIG_HOME/phoneapi`). Override with `PHONEAPI_PKG` or `PAPI_PAIRING` if needed. `pair` writes the pairing file and does not print the token. `server` forwards the on-device API to localhost; run it again if the tunnel drops.

Open PhoneAPI for screen reading and taps, wireless debugging, and the computers that have access. That switch starts the helper and shuts it down. On Android 11 and newer the same screen can turn Wireless Debugging on and pair it. You can remove a computer's access there at any time.

## Pairing

A paired computer holds its own bearer token. Mint one with `phoneapi pair`. The shell user is already trusted, so no approval on the phone is needed:

```sh
phoneapi pair --name laptop
phoneapi server
```

The saved JSON looks like this (`scheme` `http`, host `127.0.0.1`):

```json
{"scheme":"http","host":"127.0.0.1","port":41234,"token":"pa_…","name":"laptop"}
```

Every request needs `Authorization: Bearer <token>`. Scopes, the `access_token` query exception, token admin, and error shapes are documented in [docs/api.md](docs/api.md).

`phoneapi papi` reads the pairing file (or `$PAPI_PAIRING`):

```sh
phoneapi papi GET /v1/device
phoneapi papi POST /v1/input/tap '{"x":540,"y":1200,"humanize":false}'
phoneapi papi WS /v1/events
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
phoneapi helper
```

The process detaches, so the command returns while the helper keeps running. That USB start does not survive a reboot; run it again after reboot, or start the listener and helper over Wireless Debugging. On Android 11+, after pairing Wireless Debugging once, the app restarts the helper itself after a crash or a package update while its process is running. Turning the helper switch off shuts that process down and leaves it stopped until the switch is turned on again. A successful launch leaves Wireless Debugging on, because that connection is also how the browser API reaches Chrome. Granting `WRITE_SECURE_SETTINGS` is what allows the app to turn Wireless Debugging on.

On Android 10, browser calls use a USB tunnel instead. `phoneapi helper` sets that up.

To pair Wireless Debugging from the phone (Android 11+), tap "Pair wireless debugging" in the app. It opens Developer options and watches mDNS for this phone's pairing service. When the system "Pair device with pairing code" dialog opens, a heads-up notification asks for the code inline. The dialog has to stay in front: Android closes the pairing port when it is dismissed, including when the user switches apps. On Android 10 that row is hidden.

Snapshots, injected input, screenshots, logcat, browser routes, and streams need the helper. App launch and wake still run without it (with limited fallbacks). See [docs/api.md](docs/api.md) for which routes require the helper or the browser scope.

## Usage

Read the screen and tap a control:

```sh
phoneapi papi GET '/v1/ui/snapshot?format=compact'
phoneapi papi POST /v1/ui/find '{"selector":{"text":"Settings"}}'
phoneapi papi POST /v1/input/tap '{"selector":{"text":"Settings"}}'
```

Wait until a window is in front, then launch or stop an app:

```sh
phoneapi papi POST /v1/wait \
  '{"all":[{"type":"window","package":"com.android.settings"}],"timeoutMs":10000}'
phoneapi papi POST /v1/apps/com.android.settings/launch
phoneapi papi POST /v1/apps/com.android.settings/stop
```

Drive Chrome (helper up, Chrome publishing `@chrome_devtools_remote`, Wireless Debugging on Android 11+, or the Android 10 USB tunnel). Target ids look like `chrome_devtools_remote~<page id>`:

```sh
phoneapi papi GET /v1/browser/targets
phoneapi papi POST /v1/browser/tabs '{"url":"https://example.com/"}'
phoneapi papi POST /v1/browser/targets/TARGET/navigate '{"url":"https://example.org/"}'
phoneapi papi GET /v1/browser/targets/TARGET/snapshot
phoneapi papi POST /v1/browser/targets/TARGET/tap '{"selector":"a"}'
phoneapi papi POST /v1/browser/targets/TARGET/evaluate '{"expression":"document.title"}'
```

Open the viewer at `http://127.0.0.1:<port>/viewer` with the stream scope (after `phoneapi server`). It plays `WS /v1/stream/video` and `WS /v1/stream/audio` with WebCodecs.

### MCP

Point an MCP client at `http://127.0.0.1:<port>/mcp` with the same bearer token, or use the stdio bridge:

```sh
phoneapi mcp
```

Cursor example:

```json
{
  "mcpServers": {
    "phone": {
      "command": "/absolute/path/to/phoneapi",
      "args": ["mcp"]
    }
  }
}
```

Tool list, resources, scopes, and the OpenAPI document are in [docs/api.md](docs/api.md).

## License

Copyright 2026 Aaron Hopkins and contributors. All rights reserved.

Licensed under the [Apache License, Version 2.0](LICENSE).
