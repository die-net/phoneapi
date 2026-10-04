# PhoneAPI HTTP and MCP API

After `phoneapi server`, the base URL is `http://127.0.0.1:<port>` from the pairing file. The machine-readable contract is **OpenAPI 3.1** at:

```sh
phoneapi papi GET /v1/openapi.json
```

That document is generated from the same Kotlin types as the handlers and MCP tool schemas (`api-model`). Each operation has `x-scope`. WebSocket routes are marked `x-websocket`. The `bearer` security scheme is Bearer auth. Prefer OpenAPI (or this page’s summary) over guessing field names.

Install, pairing, and the helper lifecycle are in the [README](../README.md). Design background is in [architecture.md](architecture.md).

## Authentication

| Mechanism | Where |
| --- | --- |
| `Authorization: Bearer <token>` | All REST calls and `POST /mcp` |
| Cookie `phoneapi` | Set by `GET /viewer?access_token=`; sent on later requests, including WebSocket upgrades |
| `?access_token=<token>` | Only that viewer URL (it redirects and drops the query) and WebSocket upgrades |

Missing or unknown tokens get an **empty HTTP 404** (no JSON body). Authenticated calls that lack a required scope get **403** `forbidden`.

Scopes on a token:

| Scope | Use |
| --- | --- |
| `observe` | Device info, UI tree, screenshot, events, OpenAPI, wait (non-browser) |
| `control` | Input, IME, wake/unlock/lock, app launch/stop/clear, intents |
| `browser` | Chrome/WebView CDP helpers; also required for `browser.*` wait conditions |
| `stream` | Viewer page and video/audio WebSockets |
| `admin` | Token CRUD and stored unlock PIN |

ADB `CREATE_TOKEN` (via `phoneapi pair`) grants every scope. Narrower tokens: `POST /v1/tokens`.

## Conventions

- JSON bodies use `Content-Type: application/json`. Many `POST`s accept `{}` when all fields have defaults.
- Boolean query flags: bare `?name`, `true`/`1`/`yes`, or `false`/`0`/`no`.
- Closed enums (node state, swipe direction, log level, node action names, …) are case-insensitive. An unknown value is **400** `bad_request` on REST and a tool error on MCP.
- `humanize` defaults to **true** on taps/swipes (including browser taps). Set `false` for exact points.
- `/v1/device.capabilities` (and MCP `device_info`) say what works right now. MCP omits tools the token or device cannot use.

### UI snapshot

`GET /v1/ui/snapshot`

| Query | Default | Meaning |
| --- | --- | --- |
| `format` | `compact` | `compact`, `json`, or `both` |
| `window` | — | Window id |
| `maxDepth` | — | Truncate tree depth |
| `includeInvisible` | `false` | |
| `all` | `false` | All windows, not only actionable |
| `autoWake` | `true` | Wake before capture |

Compact text looks like `[e12] button "Sign in"`. Prefer it over screenshots for agents. Node `ref` values (e.g. `e12`) are stable only when `capabilities` includes `ui.stableIds` (Android 13+).

### Wait

`POST /v1/wait` body `WaitRequest`: `all` and/or `any` lists of conditions, `timeoutMs` (default 10000), optional `settleMs`, optional fresh `snapshot` / `snapshotFormat` in the result.

Condition `type` values include `node`, `window`, `ime`, `idle`, `screen`, `keyguard`, and `browser.url`, `browser.lifecycle`, `browser.networkIdle`, `browser.element`, `browser.request`, `browser.dialog`, `browser.newTarget`, `browser.log`, `browser.settled`. Browser conditions need the **browser** scope.

### Events

`WS /v1/events?types=&logcatTag=&logcatLevel=`

- `types` — comma-separated; exact name or prefix ending in `.` (e.g. `ime.`). Omit for the default set **without** logcat.
- Include `logcat` in `types` to stream log lines (helper required).
- Event names include `ui.changed`, `window.changed`, `ime.shown`, `ime.hidden`, `screen.on`, `screen.off`, `user.present`, `toast`, `logcat`, `helper.status`.

## Errors

Authenticated errors are JSON:

```json
{
  "error": "helper_unavailable",
  "message": "This requires the shell helper, which is not running. …",
  "state": { "screen": "on", "keyguard": "none", "ime": false, "foregroundPackage": "…" }
}
```

| Status | `error` | Typical cause |
| --- | --- | --- |
| 400 | `bad_request` | Bad JSON, enum, or query |
| 403 | `forbidden` | Missing scope |
| 404 | `not_found` | Unknown id/ref (authenticated) |
| 404 | *(empty body)* | Missing/invalid Bearer |
| 409 | `needs_user` / `no_keyboard` / … | Unlock or typing cannot proceed automatically |
| 503 | `helper_unavailable` | Helper down or dropped; message may say starting / needs pairing / needs USB / stopped |
| 500 | `internal` | Unexpected failure |

## REST

### Device

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/device` | observe | `DeviceInfo`: display, helper status, capabilities |
| `POST` | `/v1/device/wake` | control | Turn the screen on → `ActionResult` |
| `POST` | `/v1/device/unlock` | control | Dismiss keyguard (`UnlockRequest`, optional) |
| `POST` | `/v1/device/lock` | control | Lock the device |
| `PUT` | `/v1/device/pin` | admin | Store unlock PIN (`SetPinRequest`) → `PinStatus` |
| `DELETE` | `/v1/device/pin` | admin | Clear stored PIN → 204 |

### UI and screenshot

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/ui/snapshot` | observe | Accessibility / UiAutomation tree → `UiSnapshot` |
| `POST` | `/v1/ui/find` | observe | Match `NodeSelector` → `FindResult` |
| `POST` | `/v1/ui/nodes/{ref}/action` | control | `NodeActionRequest` (`mode` `real` or `semantic`) |
| `GET` | `/v1/screenshot` | observe | PNG; `?scale=` 0.1–1 |

### Input and IME

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `POST` | `/v1/input/tap` | control | Point or selector (`TapRequest`) |
| `POST` | `/v1/input/swipe` | control | Points, or selector + `direction` |
| `POST` | `/v1/input/gesture` | control | Pointer paths (`GestureRequest`) |
| `POST` | `/v1/input/key` | control | `KeyRequest` (`BACK`, `HOME`, `KEYCODE_*`, …) |
| `POST` | `/v1/input/text` | control | `TextRequest`; `mode` `auto` / `keyboard` / `keyevent` / `setText` |
| `POST` | `/v1/ime/hide` | control | Hide soft keyboard |
| `POST` | `/v1/ime/show` | control | Focus editable + show IME (`ImeShowRequest`) |

`autoWake` (default true) turns the screen on and leaves the keyguard up. Dismiss the keyguard with `POST /v1/device/unlock`.

`/viewer` does not post a finished swipe. It streams the canvas on `WS /v1/input/pointer` so the phone moves while the finger is still down.

### Apps and intents

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/apps` | observe | Installed apps → `AppInfo[]` |
| `POST` | `/v1/apps/{pkg}/launch` | control | `LaunchRequest` |
| `POST` | `/v1/apps/{pkg}/stop` | control | Force-stop (helper) |
| `POST` | `/v1/apps/{pkg}/clear` | control | Clear data (helper) |
| `POST` | `/v1/intents` | control | `IntentRequest` |

### Wait

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `POST` | `/v1/wait` | observe (+ browser for `browser.*`) | `WaitRequest` → `WaitResult` |

### Browser (CDP helpers)

Target ids look like `chrome_devtools_remote~<pageId>`. Chrome must publish DevTools; Android 11+ needs Wireless Debugging; Android 10 needs the USB tunnel from `phoneapi helper`.

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/browser/targets` | browser | Tabs and debuggable WebViews |
| `POST` | `/v1/browser/tabs` | browser | Open tab (`OpenTabRequest`) |
| `POST` | `/v1/browser/targets/{id}/navigate` | browser | `NavigateRequest` |
| `GET` | `/v1/browser/targets/{id}/snapshot` | browser | Compact AX outline → `BrowserSnapshot` |
| `POST` | `/v1/browser/targets/{id}/tap` | browser | Ref or CSS. `input` `touch` (default) or `cdp` (`BrowserTapRequest`) |
| `POST` | `/v1/browser/targets/{id}/swipe` | browser | CSS pixels or a direction. `input` `touch` or `cdp` (`BrowserSwipeRequest`) |
| `POST` | `/v1/browser/targets/{id}/gesture` | browser | Pointer paths in CSS pixels. `input` `touch` or `cdp` (`BrowserGestureRequest`) |
| `POST` | `/v1/browser/targets/{id}/key` | browser | Key name. `input` `touch` or `cdp` (`BrowserKeyRequest`) |
| `POST` | `/v1/browser/targets/{id}/text` | browser | Type. `touch` uses text modes; `cdp` inserts (`BrowserTextRequest`) |
| `POST` | `/v1/browser/targets/{id}/evaluate` | browser | Isolated-world JS (`EvalRequest`) → `EvalResult` |
| `POST` | `/v1/browser/targets/{id}/console` | browser | Collect logs for a timeout (`ConsoleRequest`) |

### Tokens and schema

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/tokens` | admin | `TokenInfo[]` |
| `POST` | `/v1/tokens` | admin | `CreateTokenRequest` → `CreatedToken` (201; secret once) |
| `DELETE` | `/v1/tokens/{id}` | admin | Revoke → 204 |
| `GET` | `/v1/openapi.json` | observe | OpenAPI 3.1 document |

## WebSockets

Auth: Bearer header, the `phoneapi` cookie, or `access_token` on the upgrade URL.

| Path | Scope | Summary |
| --- | --- | --- |
| `/v1/events` | observe | JSON event frames (see Conventions) |
| `/v1/input/pointer` | control | Live pointer contacts (`PointerFrame` JSON text frames) |
| `/v1/browser/targets/{id}/cdp` | browser | Raw CDP |
| `/v1/stream/video` | stream | H.264; query `maxSize`, `fps`, `bitRate` |
| `/v1/stream/audio` | stream | Device audio (submix capability) |

`/v1/input/pointer` frames are `{"op":"down"|"move"|"up","id":0,"x":1,"y":2,"tMs":0}` or `{"op":"cancel"}`. `id` is the contact, 0 through 9. `tMs` is milliseconds since that gesture's first `down`, shared by every contact. `x` and `y` are screen pixels. The first `down` is `ACTION_DOWN`; another finger is `ACTION_POINTER_DOWN`. A `move` carries the latest point of every finger still down. The last `up` is `ACTION_UP`. `cancel`, a bad frame, or the socket closing while a contact is down injects `ACTION_CANCEL` and releases the touch. An 11th `down`, or a `move`/`up` for an id that is not down, is ignored. Closing with nothing down injects nothing.

`GET /viewer?access_token=` (stream) sets the `phoneapi` cookie and redirects to `/viewer`. The page then uses that cookie.

## MCP

`POST /mcp` is a stateless Streamable HTTP MCP endpoint (same Bearer token; **not** `access_token`). It does not restrict the Host header.

For stdio hosts (Cursor, etc.), run `phoneapi mcp` (with the helper up). It installs the same localhost `adb forward` as `phoneapi server` and relays one JSON-RPC line at a time to `/mcp`. For Streamable HTTP clients, run `phoneapi server` and `POST` to `http://127.0.0.1:<port>/mcp` with the bearer token.

### Tools

| Tool | Scope | Summary |
| --- | --- | --- |
| `device_info` | observe | Device + helper + capabilities |
| `ui_snapshot` | observe | Compact UI outline |
| `ui_find` | observe | Find by selector |
| `ui_act` | control | Action on a ref; `mode` `real` or `semantic` |
| `tap` | control | Point or selector; humanized by default |
| `swipe` | control | From/to or direction inside selector |
| `type_text` | control | `mode` auto / keyboard / keyevent / setText |
| `press_key` | control | Key names |
| `wait_for` | observe | Same conditions as `/v1/wait` |
| `keyboard_hide` / `keyboard_show` | control | Soft keyboard |
| `app_launch` / `app_stop` / `app_clear` | control | Package control (`stop`/`clear` need helper) |
| `open_intent` | control | Start an intent |
| `device_wake` / `device_unlock` / `device_lock` | control | Power / keyguard |
| `browser_targets` / `browser_open` / `browser_navigate` | browser | Tab list and navigation |
| `browser_snapshot` / `browser_tap` / `browser_swipe` / `browser_gesture` | browser | AX outline and pointer input. `input` `touch` (default) or `cdp` |
| `browser_key` / `browser_text` | browser | Key and text. `cdp` stays on a background tab; device keys such as HOME need `touch` |
| `browser_eval` / `browser_console` | browser | JS and logs |
| `screenshot` | observe | PNG last resort (`scale` default 0.5) |
| `logcat_tail` | observe | Recent lines via helper |

When node ids are not stable, tool descriptions say so. Raw CDP, streams, and token admin are REST-only.

### Resources

| URI | Description |
| --- | --- |
| `phoneapi://device/capabilities` | Current capability flags |
| `phoneapi://viewer` | How to open `/viewer` with `access_token` |

## Enumerations (common)

| Field | Values |
| --- | --- |
| Wait node `state` | `present`, `absent`, `visible`, `enabled`, `disabled`, `checked`, `unchecked`, `focused`, `selected` |
| Browser element `by` | `css`, `xpath`, `text` |
| Browser element `state` | `present`, `absent`, `visible` |
| Browser log `level` | `verbose`, `info`, `warning`, `error` |
| Swipe `direction` | `up`, `down`, `left`, `right` |
| Text `mode` | `auto`, `keyboard`, `keyevent`, `setText` |
| Browser `input` | `touch` (default; bring the tab forward and inject a hardware event), `cdp` (dispatch on that target, including a background tab). Swipe and gesture coordinates are CSS viewport pixels. |
| Node `action` | `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`, `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`, plus other platform / app-listed names. `imeEnter` needs Android 11. |

Full request and response schemas live in OpenAPI and under `api-model` (`DeviceInfo`, `UiSnapshot`, `TapRequest`, `WaitRequest`, `BrowserTarget`, `ApiError`, …).
