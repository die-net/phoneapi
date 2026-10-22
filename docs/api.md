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
- `humanize` defaults to **true** on taps, swipes, and gestures (including browser). Set `false` for exact points and a fixed contact.
- `/v1/device.capabilities` (and MCP `device_info`) say what works right now. MCP omits tools the token or device cannot use.

### Screen lease

One client at a time holds the screen, as [sharing.md](sharing.md) describes. Calls that act on the screen or show what is on it take the lease: input, IME, apps and intents, wake, unlock, lock, orientation, the UI tree, screenshots, waits with any condition that is not `browser.*`, browser input with `input` `touch` (and `cdp` for pointer input), and browser reads and snapshots with `show`. Reading a tab, and `cdp` keys, text, and `cdp-mouse` taps, need no lease.

- An MCP session keeps the screen from its first such call until the session ends, it calls `screen_release`, or it makes no screen call for 30 s while someone else waits. Then cleanup runs and the waiter gets the screen; the session keeps its tabs, and its next screen call waits again and gets a note. A REST call holds the screen only while it runs.
- A call that needs the screen while someone else has it waits up to 60 s, then fails with 409 `screen_busy`, saying how long the screen has been held.
- When a holder lets go, PhoneAPI hides the keyboard, puts Chrome on a blank placeholder tab, goes home, and locks the phone before the next holder gets the screen. A step that failed is reported to the next holder: as a `# note:` line first in an MCP tool result, or an `X-PhoneAPI-Note` header on REST.
- While Chrome is in front, it shows the screen holder's tab or the placeholder, never another MCP session's tab. A session without the screen that opens a tab, or whose page opens a popup, gets it behind, with `tookScreen` false.
- A browser input, `browser_eval`, `browser_navigate`, or `browser_open` call from a session without the screen that brings an app forward fails with 409 `took_screen`. If no one held the screen, that session now does; otherwise the app that was in front comes back and the holder gets a note. Android and Chrome block nearly all of these, so this is a tripwire for workflows that rely on it.

### UI snapshot

`GET /v1/ui/snapshot`

| Query | Default | Meaning |
| --- | --- | --- |
| `format` | `compact` | `compact`, `json`, or `both` |
| `window` | — | Window id |
| `maxDepth` | — | Truncate tree depth |
| `maxNodes` | — | Most node lines in the compact outline |
| `maxChars` | — | Most characters of compact outline, cut at a line |
| `textOnly` | `false` | Leave positions, resource ids, states, and actions out of the compact outline |
| `redact` | `false` | Mask password and one-time-code fields and card numbers; `redacted` counts them. MCP `ui_snapshot` defaults to `true` |
| `includeInvisible` | `false` | |
| `all` | `false` | All windows, not only actionable |
| `autoWake` | `true` | Wake before capture |

Compact text looks like `[e12] button "Sign in"`. When anything is left out, the header line ends with `truncated` and `#` lines after it say what: `# showing 120 of 1840 nodes; raise maxNodes or maxChars for more`, `# nodes below maxDepth 4 left out`, or `# stopped reading at 5000 nodes; pass window or maxDepth to narrow`. `maxNodes` and `maxChars` apply only to the compact outline. Prefer it over screenshots for agents. Node `ref` values (e.g. `e12`) are stable only when `capabilities` includes `ui.stableIds` (Android 13+).

### Wait

`POST /v1/wait` body `WaitRequest`: `all` and/or `any` lists of conditions, `timeoutMs` (default 10000), optional `settleMs`, optional fresh `snapshot` / `snapshotFormat` in the result.

Condition `type` values include `node`, `window`, `ime`, `idle`, `screen`, `keyguard`, and `browser.url`, `browser.lifecycle`, `browser.networkIdle`, `browser.element`, `browser.request`, `browser.dialog`, `browser.newTarget`, `browser.log`, `browser.settled`. Browser conditions need the **browser** scope.

### Events

`WS /v1/events?types=&logcatTag=&logcatLevel=`

- `types` — comma-separated; exact name or prefix ending in `.` (e.g. `ime.`). Omit for the default set **without** logcat.
- Include `logcat` in `types` to stream log lines (helper required).
- Event names include `ui.changed`, `window.changed`, `ime.shown`, `ime.hidden`, `screen.on`, `screen.off`, `user.present`, `toast`, `logcat`, `helper.status`, and `screen.taken` (an MCP session without the screen brought `package` forward; `session` is its hash).

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
| 409 | `screen_busy` | Someone else held the screen for the whole 60 s wait |
| 409 | `screen_taken` | A person in the viewer took the screen from this MCP session |
| 409 | `tab_limit` | An MCP session has 4 tabs it opened, or all sessions 8 |
| 409 | `took_screen` | A call from an MCP session without the screen brought an app or Chrome forward; the message names it |
| 409 | `tab_reloaded` | Chrome had unloaded the hidden tab, so PhoneAPI reloaded it instead of sending input or script to a page the caller has not seen |
| 503 | `browser_frozen` | Android froze Chrome in the background, so nothing in it answers |
| 429 | `too_many_sessions` | `initialize` past 16 MCP sessions for the token or 32 in all |
| 503 | `helper_unavailable` | Helper down or dropped; message may say starting / needs pairing / needs USB / stopped |
| 500 | `internal` | Unexpected failure |

## REST

### Device

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/device` | observe | `DeviceInfo`: display, helper status, capabilities, whether Android's app freezer is on (`appFreezer`), and whether PhoneAPI holds Chrome's Custom Tabs binding, which keeps the freezer off Chrome while the helper runs (`chromeKept`) |
| `POST` | `/v1/device/wake` | control | Turn the screen on → `ActionResult` |
| `POST` | `/v1/device/unlock` | control | Dismiss keyguard (`UnlockRequest`, optional) |
| `POST` | `/v1/device/lock` | control | Lock the device |
| `POST` | `/v1/device/orientation` | control | Auto-rotate, or lock to `lock` / `0` / `90` / `180` / `270` (`OrientationRequest`) |
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

`autoWake` (default true) turns the screen on and leaves the keyguard up. Dismiss the keyguard with `POST /v1/device/unlock`. Browser input with `input` `cdp` or `cdp-mouse` ignores it and leaves the screen as it is.

Browser pointer input has three `input` modes. `touch` brings the tab to the front and injects a hardware touch. `cdp` sends touch events over DevTools without bringing the tab forward, but Chrome only takes them on the tab it is showing, with Chrome on screen, so `cdp` fails with `not_visible` otherwise. `cdp-mouse` taps with a DevTools mouse click, which reaches a background tab even with Chrome hidden and the screen off; the page sees a mouse rather than a finger. Keys and text with `cdp` work with Chrome hidden. All of these are trusted input. A click or scroll made from `evaluate` is not (`isTrusted` is false), which bot detection can see, and with Chrome hidden there is no trusted way to scroll: Chrome holds wheel and scroll input as it does touch.

`/viewer` does not post a finished swipe. It streams the canvas on `WS /v1/input/pointer` so the phone moves while the finger is still down.

### Apps and intents

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/apps` | observe | Installed apps → `AppInfo[]` |
| `POST` | `/v1/apps/{pkg}/launch` | control | `LaunchRequest` |
| `POST` | `/v1/apps/{pkg}/stop` | control | Force-stop (helper) |
| `POST` | `/v1/apps/{pkg}/clear` | control | Clear data (helper) |
| `POST` | `/v1/intents` | control | `IntentRequest` (helper) |

### Wait

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `POST` | `/v1/wait` | observe (+ browser for `browser.*`) | `WaitRequest` → `WaitResult` |

### Browser (CDP helpers)

Target ids look like `chrome_devtools_remote~<pageId>`. Chrome's tabs are listed with DevTools' `Target.getTargets`, so a tab has the same id however it was found, and `openerId` names the tab that opened a popup. Tabs Chrome has unloaded can be missing from the list, and the blank placeholder tab PhoneAPI keeps for the screen is left out.

An MCP session owns the tabs it opens, popups they open, and unowned tabs it claims by using them; `browser_targets` lists those and unowned tabs, with `owned`, and leaves out other sessions' tabs, which every browser tool refuses as `not_found`. A session can have 4 tabs it opened and all sessions 8; past either, opening fails with `tab_limit`. When a session ends, the tabs it opened close and those it claimed go back to being unowned; tabs left by a restart close when the helper next starts. The screen holder can also use the WebViews of the app in front. REST and the raw CDP socket can use any tab a live session does not own. A tab Chrome unloaded to save memory is reloaded in the background when used: reads, finds, and snapshots return the fresh page with `reloaded` true, and input and `eval` fail with `tab_reloaded`. Chrome must publish DevTools; Android 11+ needs Wireless Debugging; Android 10 needs the USB tunnel from `phoneapi helper`.

| Method | Path | Scope | Summary |
| --- | --- | --- | --- |
| `GET` | `/v1/browser/targets` | browser | Tabs and debuggable WebViews |
| `POST` | `/v1/browser/tabs` | browser | Open tab (`OpenTabRequest`). `tookScreen` is true when Chrome was on screen and switched to it |
| `POST` | `/v1/browser/targets/{id}/navigate` | browser | `NavigateRequest` |
| `POST` | `/v1/browser/targets/{id}/close` | browser | Close the tab without bringing it forward → `ActionResult` |
| `GET` | `/v1/browser/targets/{id}/snapshot` | browser | Compact AX outline → `BrowserSnapshot`. `maxNodes` (default 400, up to 5000) and `maxChars` cap it; cuts are reported as for the UI snapshot. `show` brings the tab on screen first. `shown` and `pending` as for `read` |
| `POST` | `/v1/browser/targets/{id}/read` | browser | `BrowserReadRequest` → `BrowserReadResult`: the page's text as markdown or plain text, a slice at a time, or its outline. `shown` says whether Chrome was drawing the tab, and `pending` lists signs of content still to load. `show` brings the tab on screen first and waits for its text to stop growing |
| `POST` | `/v1/browser/targets/{id}/find` | browser | `BrowserFindRequest` → `BrowserFindResult`: text matches or links, at the offsets `read` uses |
| `POST` | `/v1/browser/targets/{id}/tap` | browser | Ref or CSS. `input` `auto` (default), `touch`, `cdp`, or `cdp-mouse` (`BrowserTapRequest`) |
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
| `DELETE` | `/v1/tokens/{id}` | admin | Revoke → 204. Ends the token's MCP sessions within 30 s |
| `GET` | `/v1/sessions` | admin | `SessionInfo[]`: MCP sessions by a hash of their id, with age, idle time, whether they hold the screen, their tab count, how often they brought an app forward without it (`tookScreen`), and for one from `session_start`, the hash of the session that started it (`parent`) |
| `DELETE` | `/v1/sessions/{hash}` | admin | End an MCP session → 204 |
| `GET` | `/v1/openapi.json` | observe | OpenAPI 3.1 document |

## WebSockets

Auth: Bearer header, the `phoneapi` cookie, or `access_token` on the upgrade URL. A browser's upgrade must come from the API's own address: one whose `Origin` names another host or port gets the empty 404, so another local web page cannot open these sockets with the viewer's cookie.

| Path | Scope | Summary |
| --- | --- | --- |
| `/v1/events` | observe | JSON event frames (see Conventions) |
| `/v1/input/pointer` | control | The viewer's input: live pointer contacts (`PointerFrame` JSON text frames), keys, text, orientation, and taking the screen over. The socket is a holder of the screen lease |
| `/v1/browser/targets/{id}/cdp` | browser | Raw CDP |
| `/v1/stream/video` | stream | H.264; query `maxSize`, `fps`, `bitRate` |
| `/v1/stream/audio` | stream | Device audio (submix capability) |

`/v1/input/pointer` frames are `{"op":"down"|"move"|"up","id":0,"x":1,"y":2,"tMs":0}` or `{"op":"cancel"}`. `id` is the contact, 0 through 9. `tMs` is milliseconds since that gesture's first `down`, shared by every contact. `x` and `y` are screen pixels. The first `down` is `ACTION_DOWN`; another finger is `ACTION_POINTER_DOWN`. A `move` carries the latest point of every finger still down. The last `up` is `ACTION_UP`. `cancel`, a bad frame, or the socket closing while a contact is down injects `ACTION_CANCEL` and releases the touch. An 11th `down`, or a `move`/`up` for an id that is not down, is ignored. Closing with nothing down injects nothing.

The socket also takes `{"op":"key","key":"HOME"}` (a `KeyRequest`), `{"op":"text","text":"hi"}` (a `TextRequest`), `{"op":"orientation","orientation":"90"}` (an `OrientationRequest`), and `{"op":"take"}`. It holds the screen lease for the person using it: the first `down`, key, text, or orientation takes the screen if no one has it. While someone else has it, input is dropped, a whole gesture at a time. `take` takes the screen at once, even from an MCP session, which gets it back without cleanup when the socket lets go. The socket lets the screen go after 30 s without input, and when it closes. The server sends `{"op":"lease","holder":"none"|"you"|"agent"|"person"|"script","heldMs":12000}` when the holder changes, with `"takenFrom":"agent"` after a take over, and `{"op":"error","message":"..."}` when a key, text, or orientation fails.

`GET /viewer?access_token=` (stream) sets the `phoneapi` cookie and redirects to `/viewer`. The page then uses that cookie. It closes the video socket while the page is hidden and opens it again when the page is visible. While visible, it reopens the video socket after a close, and after 5 s with no picture, backing off to one attempt every 10 s. The video socket sends at least one frame a second, even from a still screen, and closes with `The shell helper stopped` if the helper dies. It opens the audio socket only while the browser will play sound. One button cycles `POST /v1/device/orientation` through `auto`, `0`, `90`, `180`, and `270`.

## MCP

`/mcp` is a stateful Streamable HTTP MCP endpoint (same Bearer token; **not** `access_token`). It does not restrict the Host header.

- `initialize` starts a session, and the response's `Mcp-Session-Id` header names it. Every later request carries that header and the token that started the session; any other token gets the empty 404 an unknown token gets. An unknown or ended session gets 404, and the client starts again with `initialize`.
- POSTs are answered with JSON. `GET` is refused with 405, since PhoneAPI sends nothing unprompted. `DELETE` ends the session, which cancels its calls in progress; those get 404.
- A session ends after 20 minutes without a request, and when its token is revoked or replaced. A token can have 16 sessions open and the phone 32; past either, `initialize` fails with 429 `too_many_sessions`.
- Sessions live in the app's memory, so restarting the app ends them all.
- The `initialize` result's `instructions` give the model a short overview of sharing the phone: which calls wait for the screen, `screen_release`, and `session_start` for subagents and parallel tasks.

Run `phoneapi server` and point a Streamable HTTP client at `http://127.0.0.1:<port>/mcp` with the bearer token. There is no stdio bridge: `phoneapi mcp` was retired when sessions arrived.

### Tools

| Tool | Scope | Summary |
| --- | --- | --- |
| `device_info` | observe | Device + helper + capabilities |
| `screen_release` | observe | Let the screen go for now, with cleanup; tabs stay the session's |
| `session_start` / `session_end` | observe | A separate session for a subagent or parallel task, with its own turn at the screen and its own tabs. Every other tool takes `session` with the id `session_start` returns; without it a call acts as the caller's own session |
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
| `open_intent` | control | Start an intent (needs helper) |
| `device_wake` / `device_unlock` / `device_lock` | control | Power / keyguard |
| `device_orientation` | control | Auto-rotate, or lock the screen to the current or a named rotation |
| `browser_targets` / `browser_open` / `browser_navigate` / `browser_close` | browser | Tab list, navigation, and closing a tab |
| `browser_snapshot` / `browser_tap` / `browser_swipe` / `browser_gesture` | browser | AX outline and pointer input. `input` `auto` (default), `touch`, `cdp`, or `cdp-mouse` (tap only) |
| `browser_key` / `browser_type` | browser | Key presses and typing. `cdp` stays on a background tab; device keys such as HOME need `touch` |
| `browser_read` | browser | Page text as markdown, sliced by `offset` and `maxChars` (default 8000), or `outline` for headings with offsets. `scope` `article` (default, via Readability) or `page`, or a CSS `selector`. `url` opens a new tab and reads it in one call. A tab that is not on screen is not drawn, so content that loads when drawn may be missing: the header says `hidden` and lists `pending` signs, and `show` brings the tab forward first |
| `browser_find` | browser | `kind` `text` (matches with context and nearest heading) or `links` (text, URL, same-origin), at offsets `browser_read` takes |
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
| Screen `orientation` | `auto`, `lock`, `0`, `90`, `180`, `270`. `0` is the device's natural orientation. `display.autoRotate` reports the system setting; `display.rotation` is the current quarter-turn (`0`–`3`). An app can still force its own orientation. |
| Text `mode` | `auto`, `keyboard`, `keyevent`, `setText` |
| Browser `input` | `auto` (default; `touch` for an MCP session that holds the screen, and otherwise `cdp-mouse` for a tap and `cdp` for keys and text, so it does not take a free screen; swipes and gestures use `touch`; a REST call never holds the screen beforehand, so it gets the DevTools modes), `touch` (bring the tab forward and inject a hardware event), `cdp` (dispatch over DevTools; touch needs the tab showing in Chrome on screen, keys and text reach a background tab), `cdp-mouse` (a mouse click that reaches a background tab, taps only). Swipe and gesture coordinates are CSS viewport pixels. |
| Node `action` | `click`, `longClick`, `focus`, `clearFocus`, `select`, `setText`, `scrollForward`, `scrollBackward`, `expand`, `collapse`, `dismiss`, `showOnScreen`, `imeEnter`, plus other platform / app-listed names. `imeEnter` needs Android 11. |

Full request and response schemas live in OpenAPI and under `api-model` (`DeviceInfo`, `UiSnapshot`, `TapRequest`, `WaitRequest`, `BrowserTarget`, `ApiError`, …).
