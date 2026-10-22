# Sharing the phone

Phase 1, below, is built: MCP sessions, the screen lease with its queue and cleanup, the viewer's Take over, REST calls as one-call holders, the security limits, and browser input defaulting to `auto`. Phase 2, tab ownership and keeping background tabs working, is still a design. [api.md](api.md) describes what exists.

Several clients can drive one phone at once: two agent conversations, an agent and a person in `/viewer`, or a script on REST. Without rules, one taps while the other types, one navigates a tab the other is reading, or one sees what the other left on screen. This design lets them share the phone without that, and lets background Chrome tabs be used freely while someone else has the screen.

PhoneAPI owns the rules, because only it knows which calls take the screen and which tabs exist. A client only decides how long its MCP session lives. Legwork's side, one session per turn, is in Legwork's [phone-leases.md](../../legwork/docs/phone-leases.md). The model calls no lock tools.

## Goals and limits

- No client drives the screen while another holds it, and none reads, navigates, or closes another's tabs.
- Background tabs need no lock. Reading and cdp input on a client's own tabs run while someone else holds the screen.
- Background means background. Chrome never shows a tab whose owner lacks the screen, so a tab behaves the same whether one conversation is running or several. A workflow that only works while its tab is on screen fails every time, not only under load.
- Waiting is the tool call blocking, not the model polling.
- The next holder starts from a known screen: home, keyboard hidden, locked.
- Every client gets the same rules: MCP clients on HTTP, a person in `/viewer`, and REST. The `phoneapi mcp` stdio relay is retired, as in [The stdio relay](#the-stdio-relay).
- Not a privacy boundary. Every client uses the same Google and app accounts on the phone, so Chrome history, Maps searches, and app state are shared whatever cleanup does. `device_info` and the `state` on errors still show the foreground package. Cleanup keeps one conversation's screen from greeting the next.
- Not isolation between sites. Tab ownership separates tabs, not cookies, logins, or what a site keeps on its servers. Two conversations on one site can log each other out, share a shopping cart, or trip a site that refuses a second open tab. That is the same problem as two threads editing one Google Doc, and a tab lock cannot fix it. Incognito would separate the state, but it would also drop the logged-in accounts that are the point of using the phone.
- Not a security boundary either. Any token with the right scope can act as a viewer or open a session. ADB stays the trust boundary.

## Holders

A lease belongs to a holder. There are three kinds:

| Holder | Starts | Ends |
| --- | --- | --- |
| MCP session | `initialize` on `/mcp` | `DELETE`, or idle expiry |
| Viewer | Its input socket opening | The socket closing |
| REST call | The request | The response |

The screen lease and tab ownership are keyed by holder, not by `Mcp-Session-Id`, so the viewer and REST follow the same queue.

## MCP sessions

`/mcp` becomes stateful. `initialize` assigns an `Mcp-Session-Id`, which is unguessable, so it needs no separate owner field. Every later request carries it.

- A request must carry the token that started the session. A session's tools and scopes are fixed at `initialize`, so another token could otherwise use the first one's scopes. A different token gets the empty 404 an unknown token gets.
- An unknown or expired session id gets 404, as the Streamable HTTP spec says, and the client starts a new session.
- `DELETE` ends the session. That releases the screen, runs cleanup, and closes the session's tabs.
- A session with no requests for 20 minutes expires, and expiry does what `DELETE` does. The timeout is a backstop, not the normal release. It is longer than a Legwork turn waiting on an Approve button (up to 15 minutes), so PhoneAPI never takes the screen from a live session someone is waiting on. It is the same for every client, and a client cannot ask for another.
- The app keeps sessions in memory, so restarting it ends them all. Clients see 404 and start again. PhoneAPI saves the ids of tabs that sessions opened and closes any that are left when it starts, so a restart does not strand them. Tabs a session only claimed are not saved, since they were not its to close.

The MCP SDK (0.15.0) has a stateful transport with session ids, `DELETE`, and open and close callbacks, but no idle timeout, and its Ktor helper keeps the session table private. PhoneAPI therefore owns the `/mcp` route and drives `StreamableHttpServerTransport` itself, so expiry, the token check, and release live in one place.

A prototype on the Android 16 emulator showed, on 2026-10-20:

- `initialize` assigns a random session id. `notifications/cancelled` cancels the tool's coroutine at once. `DELETE` cancels every call in flight, and the session then answers 404.
- The id a tool handler sees, `ClientConnection.sessionId`, is the SDK's own, not the `Mcp-Session-Id` the client holds. PhoneAPI creates each session's transport and `Server` together, with `setSessionIdGenerator` returning an id it picked, so that id is in the tools' closure. Leases and tabs are keyed by it.
- A call cut off by `DELETE` comes back as HTTP 500 `Job was cancelled`, from PhoneAPI's error mapping. It should be a JSON-RPC error.
- Responses are JSON. The SDK's streaming mode answers a POST with an event stream that Ktor closes as soon as the transport hands the request off, before the result, so it does not work without more plumbing. In JSON mode the SDK sends a request's progress on the client's GET stream, and drops it when the client has none.

So `/mcp` answers POSTs with JSON and refuses the GET stream with 405, which the spec allows, and sends no progress for now. See [Progress](#progress).
- Nothing from the SDK, session ids included, reached logcat in a debug build. A release build uses a different logging backend and is not checked yet.

## Screen lease

One exclusive lease per phone. The first call that needs the screen takes it, and an MCP session keeps it until the session ends. PhoneAPI decides which calls need it, in the tool catalog:

- Device and app tools: `tap`, `swipe`, `type_text`, `press_key`, `ui_act`, `keyboard_*`, `app_*`, `open_intent`, `device_wake`, `device_unlock`, `device_lock`, `device_orientation`.
- Observing the screen: `ui_snapshot`, `ui_find`, `screenshot`. These would show another holder's screen.
- `wait_for` with any condition that is not `browser.*`.
- Browser input with `input` `touch`, which brings the tab forward. Pointer input with `input` `cdp` too: Chrome only takes cdp touch on the tab it is showing, so it already needs Chrome in front.
- Anything else that brings Chrome or a tab to the front.

Calls that need no lease: `device_info`, `logcat_tail`, and browser tools on the caller's own tabs with `input` `cdp` (keys and text) or `cdp-mouse`, or no input at all.

Browser input defaults to `auto`: `touch` when the caller holds the screen, and otherwise `cdp-mouse` for a tap and `cdp` for keys and text. A swipe or gesture with `auto` still needs the screen, since cdp touch only reaches a tab on screen. Without this, a tap that left `input` unset would take a free screen and keep it for the rest of the session. Passing `touch` still takes the screen on purpose.

The REST routes for the same operations follow the same list.

### Waiting

A call that needs the screen while another holder has it waits in a FIFO queue for up to 60 seconds. It sends no progress while it waits. When the time passes, the call fails with `screen_busy` and how long the lease has been held. It never says who holds it, except that a person in the viewer is reported as a person. A canceled request leaves the queue.

Only `notifications/cancelled`, or the session ending, takes a call out of the queue. A client dropping its connection does not: the prototype's tool ran to the end after its client went away, on today's stateless `/mcp` as well as on stateful sessions. That matches the spec, which says not to treat a disconnect as a cancellation. So a waiting call whose client has gone stays queued until its 60 seconds pass or its session ends, and clients have to send `notifications/cancelled` to stop a wait. Legwork's Go SDK (v1.8.0) does that when a call's context ends, sends `DELETE` when the session closes, and opens the GET stream after `initialize`.

An MCP session keeps the screen while it is idle, including a Legwork turn waiting on approval. Others' screen calls fail with `screen_busy` for that whole time. That is accepted: taking the screen from a live session would leave its next step acting on a screen it did not expect. There is no limit on how long a session holds the screen in total: idle expiry, ending the session from the admin routes, and **Take over** in the viewer cover a stuck client.

### Cleanup

When a holder releases the screen or expires, PhoneAPI runs cleanup before granting the next waiter:

1. Hide the keyboard.
2. Show the [placeholder tab](#background-tabs-stay-in-the-background), then HOME, so Chrome is in the background and shows a blank page the next time it comes forward.
3. Lock. Locking always, rather than only when someone waits, keeps "locked between holders" simple to check, and the stored PIN makes the next unlock cheap.
4. Clear the clipboard, if the helper can. Whether the shell user can clear it on Android 11 is not checked yet.

A step that fails is reported in the next holder's first result that needs the screen, so it checks the state before it acts.

A REST call releases without cleanup, so a script's steps are not undone between calls. Another holder can take the screen between two REST calls.

Chrome stays in the background after cleanup. When it comes forward for the new holder, it shows one of the holder's tabs or the placeholder, never another holder's page.

## Viewer

Watching needs no lease. The video and audio sockets show whatever is on screen, because the person at the viewer is the phone's operator.

Input does. Today the viewer sends touches on `WS /v1/input/pointer` and keys, text, and orientation as REST posts, and it opens the pointer socket on load and reconnects every 500 ms whether the page is visible or not. So holding the socket open cannot mean holding the screen. Instead:

- The viewer's keys, text, and orientation move onto its socket, next to pointer frames. The socket is the holder, and needs no extra header to tell viewer posts from a script's.
- The viewer's first input takes the screen at once if no one holds it. That input is delivered.
- If someone else holds it, the input is dropped, not queued, and the socket tells the page. The page shows that the phone is in use, by an agent or by another viewer, and for how long, with a **Take over** button.
- **Take over** takes the screen at once. The holder's call in progress is canceled. Until the screen comes back, the holder's screen calls fail at once with `screen_taken`, rather than waiting 60 seconds, so the model stops and says so instead of retrying. No cleanup runs, so the person sees what the agent left, such as a half-filled form or a captcha.
- The viewer gives the screen up after 30 seconds without input, or when its socket closes. The page shows that it has.
- If it took the screen over and that holder is still alive, the screen goes straight back to it, without cleanup, since the person may have fixed something for it. Its first result that needs the screen says a person used the screen and for how long. Otherwise cleanup runs and the queue goes on.
- The socket sends lease changes to the page, so it can show who has the screen without polling.

Agents never take the screen over. They queue behind a viewer like anyone else.

## Tabs

A tab belongs to the holder that opened it, or that claimed it. A REST call that opens a tab leaves it unowned.

- `browser_targets` lists the caller's tabs and unowned ones, marked as unowned. Other holders' tabs are left out.
- `browser_navigate`, `browser_close`, `browser_read`, `browser_find`, `browser_snapshot`, `browser_eval`, `browser_console`, browser input, and `browser.*` wait conditions refuse a tab another holder owns, the same way they refuse a tab that does not exist. `browser.newTarget` only matches tabs the caller owns.
- A tab that a page opens, through `window.open` or a `target=_blank` link, belongs to the owner of the tab that opened it. That needs Chrome's `openerId`, which `/json/list` does not report, so ownership is read from the `Target` domain (`Target.getTargets`, or `Target.setDiscoverTargets` on the browser connection). On the emulator `Target.getTargets` reported `openerId` for both, with the opener on screen, behind another tab, and with Chrome in the background.
- Tabs that existed before PhoneAPI started, tabs left by a session that died, and tabs REST opened belong to no holder. On these phones they are almost always Kira's own pages from earlier conversations, such as a signed-in shopping tab, so they stay usable. A session claims an unowned tab by using it, and the first session to do so gets it. When that session ends, the tab goes back to being unowned instead of closing.
- WebViews belong to the app that shows them. The screen holder can use the WebViews of the app in front, which it can already see with `ui_snapshot`. Other sessions cannot.
- REST and the raw CDP socket can use any tab except one a live holder owns.
- Each session can have 4 tabs open, and all sessions 8, not counting the placeholder, unowned tabs, or tabs a session claimed. Opening past either fails with `tab_limit` and a message to close one. Under memory pressure Chrome unloads hidden tabs, as in [Tabs that stop answering](#tabs-that-stop-answering). The caps keep that rare, and a few tabs at a time is what a turn needs. On the 2 GB emulator unloading started at 17 tabs, so 8 leaves room for the tab on screen, unowned tabs, and other apps on a 4 GB phone.
- The tabs a session opened close when it ends. Tabs it claimed go back to being unowned.

Whoever holds the screen can open Chrome's tab switcher and see other holders' tab titles. Given that the accounts are shared anyway, that is accepted.

### Background tabs stay in the background

Android 10 and later block an app in the background from starting activities, unless it was on screen in the last few seconds, and Chrome does not hand a navigation in a hidden tab to another app. Neither protects a tab Chrome is showing. Today a lone agent's tab often is: Chrome on Android ignores `Target.createTarget`'s background flag and switches to a new tab while Chrome is in front, which is why `tookScreen` exists. So:

- PhoneAPI keeps one `about:blank` placeholder tab that no holder owns, and opens it again if it is closed.
- Whenever Chrome is in front, it shows the screen holder's own tab or the placeholder. When Chrome selects another holder's tab, such as one just opened or a popup, PhoneAPI selects the holder's tab or the placeholder again with `Target.activateTarget`, between the holder's calls, never during one. The holder's tab is the one it last opened or brought forward with touch input or `show`. If Chrome will not switch, PhoneAPI closes the new tab and fails the call with `took_screen`.
- A popup from a tab that is not on screen becomes the tab Chrome shows while Chrome is in front, with no change of foreground app. After each browser input, `browser_eval`, and `browser_navigate` from a session without the screen, PhoneAPI gives new popups to the opener's owner and puts Chrome back as for a tab that session opened. A popup a page opens later, on a timer, is caught before the holder's next screen call.
- Selecting a tab can take the screen. On the emulator, `Target.activateTarget` and `Page.bringToFront` brought Chrome forward from the background, even 15 seconds after HOME. On the phone they only changed which tab was selected, and the launcher stayed in front. The emulator runs Chrome as a debug app with `adb root`, which may explain the difference. PhoneAPI never sends either for a session without the screen.
- Chrome stays in the background when nobody holds the screen. Cleanup shows the placeholder before HOME.
- While Chrome is in the background, PhoneAPI leaves its selected tab alone, since selecting one brings Chrome forward. It fixes the selection before each of the holder's screen calls while Chrome is in front, and after one that brought Chrome forward, such as `app_launch` of Chrome, before that call returns. It checks after selecting, since Chrome can change its selection as it comes forward. While only one session owns tabs, none of this runs.
- `tookScreen` is always false for a caller without the screen.

### Tabs that stop answering

Three things stop a hidden tab, measured on the Android 16 emulator on 2026-10-21:

- **Chrome throttles and then freezes it.** With Chrome in front and the tab behind another, its timers ran about once a second for the first minute, about once a minute after that, and stopped at about 5 minutes. A frozen tab still answers DevTools: script evaluation, reading, mouse input, and navigation all work, and only the page's own JavaScript is paused, so content it would load or poll for does not arrive. `Page.setWebLifecycleState` with `active` answered but did not restart its timers. Navigating or reloading gives a fresh page that runs until it freezes again.
- **Chrome unloads it under memory pressure.** Opening heavy pages one after another, nothing was unloaded through 15 tabs, ten of them real sites such as YouTube, CNN, Amazon, Maps, and the New York Times, with free memory down to about 220 MB. From 17 tabs, with 250 MB pages added, Chrome unloaded the oldest tabs first, six by 19 tabs. An unloaded tab stays in the tab list and answers page-level commands, but script evaluation never answers, since its renderer is gone. That matches Kira's long-hidden tabs on the phone. `Page.reload` or `Page.navigate` brings it back as a fresh load and leaves it hidden. Selecting it also reloads it, but puts it on screen. `document.wasDiscarded` stayed false, so a changed `performance.timeOrigin` is what shows the reload.
- **Android freezes all of Chrome.** On Android 12 and later the cached-app freezer is on by default. About 2 minutes after Chrome went to the background, every Chrome process was frozen, `isFrozen=true`, and nothing answered, not even the browser endpoint. Android 11 has the freezer but leaves it off by default, which fits the phone answering at the browser level.

  The freezer's effective state is in `adb shell dumpsys activity`, under "Freezer settings", as `use_freezer=true` or `false`. The setting behind it is `cached_apps_freezer` (`settings get global cached_apps_freezer`), which is unset by default. On the Android 16 emulator, changing the setting changed `use_freezer` at once, without the reboot the developer option asks for: with it `disabled`, Chrome was never frozen in 150 seconds in the background, and set back to the default, Chrome was frozen within 90 seconds. Turning it off does not thaw an app that is already frozen; that waits until the app comes to the front or the phone restarts. Whether Android 11 shows the same section and applies changes as quickly is not checked.

So PhoneAPI:

- Probes a tab with a cheap evaluation and a short timeout, about 2 seconds, before a call that needs its script, rather than waiting out the 15-second DevTools timeout.
- When the tab does not answer but the browser does, it was unloaded. PhoneAPI reloads it in the background and says so in the result, since anything typed or scrolled is gone, rather than acting on the fresh page as if nothing happened. Input calls fail instead with `tab_reloaded`, so a session does not type into a page it has not looked at again.
- When the browser does not answer either, Android has frozen Chrome, and the call fails with `browser_frozen`. PhoneAPI keeps that from happening while its helper runs, as in [Android's app freezer](#androids-app-freezer).
- Says in reads that a tab hidden for more than a few minutes is frozen, so content it would load since then is missing, next to `shown` and `pending`.

### Android's app freezer

Android only freezes an app it counts as cached, one that nothing in front of the user depends on. While the shell helper is running, PhoneAPI's foreground service binds Chrome's Custom Tabs service (`android.support.customtabs.action.CustomTabsService`), which any app may bind, so Chrome counts as bound by a foreground service, not cached. It holds the binding whether or not any client is connected, and drops it when the helper stops. The manifest declares the Custom Tabs action under `<queries>`, which Android 11 and later require before an app can see another app's service.

Checked on the Android 16 emulator, with the freezer at its default, on 2026-10-21, using a throwaway build:

- With the binding held, Chrome's browser process stayed at importance 200, the same as PhoneAPI's service, and Android froze none of Chrome's live processes in 10 minutes in the background. A hidden tab answered DevTools throughout. Its timers still stopped at about 5 minutes, which is Chrome's own freezing and leaves reads and input working.
- The renderers of hidden tabs sat at importance 900 to 910, the cached range, yet were not frozen. Android's low-memory killer can still take them, which is unloading as above.
- Without the binding, Chrome dropped to the cached range within a minute, and every Chrome process was frozen by 2 minutes.

This needs no system setting and touches only Chrome. Turning the freezer off for the whole phone, with `settings put global cached_apps_freezer disabled` or the "Suspend execution for cached apps" developer option, stays a manual fallback for a device where the binding falls short. PhoneAPI does not offer it as a setting unless that turns out to be needed. `browser_frozen` says what happened, and names the developer option.

The freezer's effective state is `use_freezer` under "Freezer settings" in `dumpsys activity`, which the helper can read. `GET /v1/device` reports it, and whether PhoneAPI holds Chrome's binding.

### Content that loads when drawn

A hidden tab is not drawn. Images that load lazily do not load, which saves work, since PhoneAPI reads text and only a screen capture shows pixels. A capture needs the screen, and the images load once the tab comes forward. But some pages also build their text only when drawn or scrolled into view: infinite scroll, "load more" sections, skeleton placeholders. In a hidden tab that text never appears, and a read looks complete.

So reads say when that may have happened:

- `browser_read` reports `shown`: whether Chrome is drawing the tab, meaning Chrome is in front with this tab selected. `document.visibilityState` alone is not enough, since a tab opened while Chrome was in the background reports `visible` while Chrome is still hidden.
- `page-pending.js` reports `pending`, next to `blocked` and `sparse`: signs the page is waiting to load content, which are a laid-out `aria-busy` region, empty skeleton, shimmer, spinner, or loading boxes, and a "Load more" or "Show more" control.
- When the tab is not shown, the result says so. When the page is also `sparse` or `pending`, it says content may not have loaded while the tab was hidden, and that reading with `show` may load it. `browser_snapshot` says the same in lines after its header.
- `browser_read` and `browser_snapshot` take `show`. It brings the tab forward, launching Chrome if it is behind, and waits until the page's text holds still for 750 ms, at most 5 seconds, then reads. Under this design it also takes the screen, waiting like any screen call. It only draws what is on screen, so content further down may still need scrolling.

This part is built, without the screen lease, since it needs no sessions.

### When something comes forward anyway

If a call from a session without the screen still brings an app or Chrome forward, a workflow is relying on something it should not, and PhoneAPI makes that loud.

- PhoneAPI already tracks the foreground package. It watches the top task, not what is visible: between holders the phone is locked, and an app launched then waits behind the lock screen for the next holder.
- A change of top task during a call by a session without the screen, or within 2 seconds after, while the holder has no call in progress, is that session's doing. A change during the holder's call is the holder's. A change with no call in progress anywhere, such as an incoming call or an alarm, belongs to no session and is reported in the holder's next result that needs the screen.
- The session's call fails with `took_screen`, naming what came forward, even if the action itself worked. PhoneAPI also sends a `screen.taken` event on `/v1/events`, logs a warning, and counts it per session in `GET /v1/sessions`.
- If nobody held the screen, the session now holds it. It caused what is showing, so it is the one that can look and clean up, such as stopping navigation that Maps started. Cleanup runs when it releases.
- If another holder had the screen, PhoneAPI brings the holder's app back to the front, and the holder's next result that needs the screen says something came forward briefly. Whatever the launched app did is not undone.
- If two sessions without the screen had calls in progress, both fail with `took_screen` and neither gets the screen. Cleanup runs instead, or the holder's app comes back.
- Chrome switching to another holder's tab, as a popup makes it do, does not change the foreground app, so it is handled as in [Background tabs stay in the background](#background-tabs-stay-in-the-background).

On the emulator, tabs that were not on screen launched no app in 18 tries: an `intent:` link, an app link, and a custom scheme, each by mouse click, by script, and by script with a user gesture, from a tab behind another and with Chrome in the background. App links opened inside the tab instead. From the tab on screen, the same clicks launched YouTube.

Android and Chrome should block nearly all of these, so `took_screen` is a tripwire for workflows to fix, not something agents handle in normal use.

## Progress

PhoneAPI sends no progress for now. The wait for the screen is capped at 60 seconds and ends in the screen or `screen_busy`, so nothing depends on it. Legwork's optional status line, its phase 4, is the one use in sight.

When something wants it, there are two ways:

- The session's GET stream. The SDK already sends a request's progress there in JSON mode, and the prototype showed it working. Clients may skip that stream, though Legwork's Go SDK opens it by default.
- Streaming POST responses, which every client must accept. The SDK's transport cannot do it through Ktor, so PhoneAPI would write its own transport on the SDK's `Transport` interface, about 250 to 350 lines. It would also own session ids, headers, and errors. Wrapping the SDK's transport instead is smaller but depends on its internals. Kotlin SDK 0.15.0, the latest release, has no fix.

## The stdio relay

`phoneapi mcp`, the bash loop that relays stdio to `/mcp`, is retired rather than rewritten for sessions. It would have needed to keep the session id, run requests concurrently so a cancellation reaches PhoneAPI while a call waits, and send `DELETE` when stdin closes. The clients in use connect over HTTP. It can come back if a stdio-only client needs it.

## Security

The spec's main warning about sessions is not to treat a session id as authentication. PhoneAPI doesn't: every request still needs the bearer token, and the token that started the session. The SDK makes ids with Kotlin's `Uuid.random()`, which uses `SecureRandom` on the JVM, so PhoneAPI keeps its generator. `/mcp`, including `GET` and `DELETE`, takes only the bearer header, never the `phoneapi` cookie or `access_token`, so a page in a browser cannot use a session even if it learns the id.

Every Legwork turn uses one token, though, so the token check does not keep one conversation out of another's session. A session id is the only thing that does, and the following keep it, and the phone, from being misused.

- **Session ids stay out of the log.** `logcat_tail` lets any session with `observe` read the log, so a full id there would let one conversation use or close another's tabs, or end its session. PhoneAPI logs a short hash of an id, never the id. The SDK's own logging is checked for ids at every level before phase 1 ships.
- **Sessions are capped.** Each session holds memory on the phone until it ends or expires. A token can have 16 sessions open and the phone 32 in all, to start. Past either, `initialize` fails with `too_many_sessions`. The SDK's event store, which keeps past messages for resuming a stream, stays off.
- **A stuck session can be ended.** Any request resets the idle timer, so a client that keeps calling holds the screen as long as it likes, and only **Take over** in the viewer breaks it. `GET /v1/sessions` (admin) lists sessions by hash, with their age, idle time, tab count, and whether they hold the screen. `DELETE /v1/sessions/{hash}` (admin) ends one as its own `DELETE` would.
- **Revoking a token ends its sessions.** Requests with a revoked token already fail, but its sessions would otherwise keep the screen, their tabs, and any queued call until they expired. Revocation ends them at once, with cleanup.
- **The viewer's socket checks `Origin`.** The `phoneapi` cookie is `SameSite=Strict`, but a page on `127.0.0.1` at any port is the same site, so another local web server's page in the same browser could open the viewer's socket with it. Today that socket injects touches. Under this design it can also take the screen over. The upgrade is refused unless `Origin` is the API's own `http://127.0.0.1:<port>`. The other WebSocket routes get the same check.

Leaks between holders are limited by the rules above: `screen_busy` says how long the screen has been held but not by whom, and tab listings show only the caller's tabs.

## Errors

| Status | `error` | Cause |
| --- | --- | --- |
| 409 | `screen_busy` | Another holder had the screen for the whole wait. The message says how long it has held it |
| 409 | `screen_taken` | A person in the viewer took the screen from this session |
| 409 | `took_screen` | A call from a session without the screen brought an app or Chrome forward. The message names it |
| 409 | `tab_limit` | The session has 4 tabs open, or all sessions 8 |
| 409 | `tab_reloaded` | Chrome had unloaded the tab, so PhoneAPI reloaded it instead of sending input to a page the session has not seen |
| 503 | `browser_frozen` | Android has frozen Chrome in the background, so its tabs cannot answer. The message names the developer option that turns the freezer off |
| 429 | `too_many_sessions` | The token has 16 sessions open, or the phone has 32 |

On MCP these are tool errors, like other `ApiException`s.

## Phases

1. Stateful `/mcp` with the token check, idle expiry, the session caps, hashed ids in the log, ending sessions on revocation, the admin session routes, and retiring `phoneapi mcp`. The `Origin` check on WebSocket upgrades. Holders, the screen lease with its queue, and cleanup. REST calls as one-call holders. The viewer's socket as a holder, with **Take over**. Browser input defaulting to `auto`.
2. Tab ownership, including opener inheritance and the startup sweep. Recovering unloaded tabs, `browser_frozen`, and holding Chrome's Custom Tabs binding while the helper runs. The placeholder tab and keeping Chrome in the background. `took_screen`. The tab caps.

Legwork's phases 3 and 4 need phase 1. Phase 2 can follow. `shown`, `pending`, and `show` on reads and snapshots needed neither phase and are already built.

## To check on the phone

The emulator runs Chrome 133, and the phone Chrome 155.0.8059.84. Checked on the phone on 2026-10-21:

- `Target.activateTarget` and `Page.bringToFront` change the selected tab without bringing Chrome forward, unlike the emulator.
- A popup from a tab not on screen becomes the tab Chrome shows while Chrome is in front, as on the emulator, and `Target.getTargets` reports its `openerId`.
- Tabs not on screen launched no app in 8 tries with `tel:` and `intent:` links, by click and by script with a user gesture. From the tab on screen, both opened the dialer.
- Page commands work on each page's own DevTools endpoint. Attaching through the browser connection got no answers.

Still to check on the phone:

- Whether the phone throttles and freezes hidden tabs on the emulator's schedule, and whether `cached_apps_freezer` is off there, as Android 11's default is.
- Whether a tab opened while Chrome is in the background becomes Chrome's selected tab. On the Android 16 emulator it does: Chrome selects it as it comes forward, even over a `Page.bringToFront` sent just before, which is why `show` checks that its tab is still showing and tries again. Opening the tab did not bring Chrome forward.
- Whether the shell user can clear the clipboard on Android 11, without clearing Kira's.
- How many hidden tabs Chrome keeps loaded on the Moto G Power before it unloads them, to check the cap of 8.
- Whether a release build logs anything from the SDK, such as session ids.
- That binding Chrome's Custom Tabs service works on a real Android 12 or later phone, and does not change anything visible on Kira's Android 11 phone.
- That `show` brings a background tab on screen, including when Chrome is behind another app, and that `shown` and `pending` match what is on screen.

## Open questions

None. The last three were settled on 2026-10-21: one idle timeout for every client, no total limit on holding the screen, and unowned tabs visible and claimable, with the screen holder able to use the foreground app's WebViews.
