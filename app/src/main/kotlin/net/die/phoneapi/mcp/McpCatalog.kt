package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.OrientationRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.schema.JsonSchemas
import net.die.phoneapi.server.ServerServices

internal data class McpTool(
    val name: String,
    val description: String,
    val scope: Scope,
    val available: (Capabilities) -> Boolean,
    val arguments: KSerializer<*>,
    val schema: ToolSchema = toolSchema(arguments),
    val annotations: ToolAnnotations? = null,
    val call: suspend (ServerServices, Set<Scope>, CallToolRequest) -> CallToolResult,
)

internal fun visibleMcpTools(scopes: Set<Scope>, capabilities: Capabilities): List<McpTool> =
    MCP_TOOLS.filter { it.scope in scopes && it.available(capabilities) }
        .map { tool ->
            val note = reducedNote(tool.name, capabilities)
            if (note == null) tool else tool.copy(description = "${tool.description} $note")
        }

internal fun mcpToolTemplates(): List<McpTool> = MCP_TOOLS

private val readOnly = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

private val changes =
    ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)

private val destructive =
    ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false)

private val always: (Capabilities) -> Boolean = { true }

private fun reducedNote(name: String, caps: Capabilities): String? =
    if (!caps.uiStableIds && name in REF_TOOLS) "Reduced mode: node ids are not stable." else null

private val REF_TOOLS =
    setOf(
        "ui_snapshot",
        "ui_find",
        "ui_act",
        "tap",
        "swipe",
        "type_text",
        "wait_for",
        "keyboard_show",
    )

private fun toolSchema(serializer: KSerializer<*>): ToolSchema {
    val schemas = JsonSchemas(refPrefix = "#/\$defs/")
    val root = schemas.inline(serializer.descriptor)
    val required = root["required"]?.jsonArray?.map { it.jsonPrimitive.content }
    return ToolSchema(
        properties = root["properties"]?.jsonObject ?: JsonObject(emptyMap()),
        required = required?.takeIf { it.isNotEmpty() },
        defs = schemas.definitions.takeIf { it.isNotEmpty() }?.let { JsonObject(it) },
    )
}

private val MCP_TOOLS: List<McpTool> =
    listOf(
        McpTool(
            name = "device_info",
            description =
                "Model, Android version, display, helper status, capabilities, and current state: screen, keyguard, keyboard, and foregroundPackage. Cheap. foregroundPackage only updates while the helper is watching the screen, as during wait_for or right after ui_snapshot, so it can be missing or stale. To know what is in front, take a ui_snapshot or wait_for a window condition.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> deviceInfo(graph) },
        ),
        McpTool(
            name = "screen_release",
            description =
                "Let the screen go when you are done with it for now, so other clients can use the phone. PhoneAPI goes home and locks it. Your tabs stay yours and background browser work keeps going. The next call that needs the screen takes it again, waiting if someone else has it. Holding the screen idle while another client waits also lets it go, after 30 s.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> screenRelease(graph) },
        ),
        McpTool(
            name = "ui_snapshot",
            description =
                "Compact outline of the visible nodes on screen. Covers app windows, plus system windows such as dialogs and the notification shade when they are focused or large. The keyboard is listed without its contents. maxNodes and maxChars cap the output. When anything is left out, the first line ends with truncated and the next lines say how much. textOnly drops positions, ids, states, and actions. Password and one-time-code fields and card numbers are masked unless redact is false. Prefer this over screenshot. Take a new one after each action. Canvas content such as maps shows little, so use screenshot there. Right after an action the tree can still be the old screen: wait_for first.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<SnapshotArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> uiSnapshot(graph, request) },
        ),
        McpTool(
            name = "ui_find",
            description =
                "Find nodes by selector and return each match's bounds and actions. Cheaper than ui_snapshot when you know what you want. Use index when several nodes match.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<FindRequest>(),
            annotations = readOnly,
            call = { graph, _, request -> uiFind(graph, request) },
        ),
        McpTool(
            name = "ui_act",
            description =
                "Act on a ref from the latest ui_snapshot or ui_find. action is click, longClick, setText, scrollForward, imeEnter, or a custom action listed on the node. mode semantic skips real touch.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<NodeActArgs>(),
            annotations = changes,
            call = { graph, _, request -> uiAct(graph, request) },
        ),
        McpTool(
            name = "tap",
            description =
                "Tap a point in screen pixels, or a node selector. Prefer a selector by id, text, or desc over a ref. Humanized by default. Uses the screen.",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<TapRequest>(),
            annotations = changes,
            call = { graph, _, request -> tap(graph, request) },
        ),
        McpTool(
            name = "swipe",
            description =
                """Swipe from one point to another, or in a direction (up, down, left, right) across the screen or inside a selector. from and to are objects such as {"x":540,"y":1800}. Uses the screen.""",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<SwipeRequest>(),
            annotations = changes,
            call = { graph, _, request -> swipe(graph, request) },
        ),
        McpTool(
            name = "type_text",
            description =
                "Type into the focused field, or into selector first. mode auto taps the on-screen keyboard and falls back to setText. setText is fastest for long text. clear replaces the field's text. submit presses the IME action. no_editor means no editable field has focus.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<TextRequest>(),
            annotations = changes,
            call = { graph, _, request -> typeText(graph, request) },
        ),
        McpTool(
            name = "press_key",
            description =
                "Press BACK, HOME, ENTER, DEL, or any KEYCODE_* name. HOME returns to the launcher. Uses the screen.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<KeyRequest>(),
            annotations = changes,
            call = { graph, _, request -> pressKey(graph, request) },
        ),
        McpTool(
            name = "wait_for",
            description =
                "Wait until all or any conditions match. Pass at least one. Use it after an action instead of sleeping or taking a screenshot. A condition's type is node, window, ime, idle, screen, keyguard, or browser.*. After open_intent or app_launch, wait for {type window, package} with {type node, selector {textContains}}. After browser_open, wait for browser.url and browser.settled on its target. idle is a fallback when no text is known. Browser conditions need the browser scope, and target when more than one page is open.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<WaitArgs>(),
            annotations = readOnly,
            call = { graph, scopes, request -> waitFor(graph, scopes, request) },
        ),
        McpTool(
            name = "keyboard_hide",
            description = "Hide the soft keyboard.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> keyboardHide(graph) },
        ),
        McpTool(
            name = "keyboard_show",
            description =
                "Show the soft keyboard for an editable node. Omit selector to use the focused field.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<ImeShowRequest>(),
            annotations = changes,
            call = { graph, _, request -> keyboardShow(graph, request) },
        ),
        McpTool(
            name = "app_launch",
            description =
                "Launch an app by package name and bring it to the front. fresh clears its task first. wait (default true) waits for it to reach the foreground, not for its content. Uses the screen.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<PackageArgs>(),
            annotations = changes,
            call = { graph, _, request -> appLaunch(graph, request) },
        ),
        McpTool(
            name = "app_stop",
            description =
                "Force-stop an app. Unsaved state in it is lost. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appStop(graph, request) },
        ),
        McpTool(
            name = "app_clear",
            description =
                "Erase an app's data, including sign-in. Not reversible. Use only when the user asked. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appClear(graph, request) },
        ),
        McpTool(
            name = "open_intent",
            description =
                "Start an intent, such as action android.intent.action.VIEW with an https URL as data. package and component are optional. Waits for the app to reach the foreground, not for its content, so follow with wait_for. Without a package, a URI that several apps handle shows a chooser and returns ok false: name a package. Uses the screen. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<IntentRequest>(),
            annotations = changes,
            call = { graph, _, request -> openIntent(graph, request) },
        ),
        McpTool(
            name = "device_wake",
            description = "Turn the screen on. Leaves the keyguard up.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceWake(graph) },
        ),
        McpTool(
            name = "device_unlock",
            description =
                "Dismiss the keyguard. A secure lock uses the stored PIN. needs_user means there is no stored PIN, or the lock is a pattern or password.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceUnlock(graph) },
        ),
        McpTool(
            name = "device_lock",
            description =
                "Turn the screen off and lock. The foreground app stays, so press HOME first for a neutral screen. Background browser work keeps running.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = destructive,
            call = { graph, _, _ -> deviceLock(graph) },
        ),
        McpTool(
            name = "device_orientation",
            description =
                "Set screen orientation. auto follows the sensor. lock freezes the current rotation. 0, 90, 180, and 270 lock to that rotation from the device's natural orientation. An app can still force its own orientation.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<OrientationRequest>(),
            annotations = changes,
            call = { graph, _, request -> deviceOrientation(graph, request) },
        ),
        McpTool(
            name = "browser_targets",
            description =
                "List Chrome tabs and debuggable WebViews with id, title, and url. Chrome updates the list itself, so a closed tab or a redirect can lag behind. Chrome must be running with USB debugging on. Android 11+ needs PhoneAPI paired with wireless debugging. Android 10 needs the USB DevTools forward.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> browserTargets(graph) },
        ),
        McpTool(
            name = "browser_open",
            description =
                "Open a tab and return its target. With Chrome hidden, the tab opens in the background and the screen is untouched. With Chrome on screen, Chrome switches to the new tab, and the result has tookScreen true. url must be http(s) or about:blank. A page that hands off to an installed app loads as a website in the tab. Follow with wait_for on browser.url and browser.settled.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<UrlArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserOpen(graph, request) },
        ),
        McpTool(
            name = "browser_navigate",
            description =
                "Navigate an existing target to url, in the background. target is the id from browser_targets or browser_open. Follow with wait_for.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetUrlArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserNavigate(graph, request) },
        ),
        McpTool(
            name = "browser_close",
            description =
                "Close a tab by target id, in the background. Works after a redirect, where window.close() does not.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserClose(graph, request) },
        ),
        McpTool(
            name = "browser_snapshot",
            description =
                "Compact outline of a browser target's accessibility tree, with refs for browser_tap. Works in the background. Shows up to maxNodes lines (default 400) and 40 levels. When anything is left out, the first line ends with truncated and the next lines say how much. Links show their text, not their URL. To read a page's text, browser_read is much smaller. A tab that is not on screen is not drawn, so content that loads when drawn may be missing; the header says so, and show brings the tab forward first.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSnapshotArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSnapshot(graph, request) },
        ),
        McpTool(
            name = "browser_read",
            description =
                "Read a page's text as markdown, a slice at a time. Works in the background. scope article (default) is the main content without navigation, footers, and ads. page is all visible text. selector reads one element, such as a table. The header gives the total length, a version, and nextOffset for the next slice, and flags a page that looks blocked or still loading. outline returns only headings with their offsets, cheaply. Pass url instead of target to open a new tab and read it in one call. A tab that is not on screen is not drawn, so content that loads when drawn may be missing; the header says so, and show brings the tab forward first.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserReadArgs>(),
            annotations = changes,
            call = { graph, scopes, request -> browserRead(graph, scopes, request) },
        ),
        McpTool(
            name = "browser_find",
            description =
                "Find text or links in a page's content, with offsets browser_read takes when given the same scope, selector, and format. Works in the background. kind text returns each match with context and its nearest heading. kind links returns each link's text, URL, and whether it is same-origin, filtered by query and hrefContains.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserFindArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserFind(graph, request) },
        ),
        McpTool(
            name = "browser_tap",
            description =
                "Tap a snapshot ref or a CSS selector in a browser target. The default input auto is touch while you hold the screen, and cdp-mouse otherwise, so tapping a background tab does not take a free screen. input touch brings the tab to the front and uses the screen. input cdp sends touch events over DevTools without bringing the tab forward, but Chrome only takes them on the tab it is showing, with Chrome on screen. input cdp-mouse clicks with a mouse, which works with Chrome hidden and the screen off, but the page sees a mouse rather than a finger. All three are trusted input, unlike a click from browser_eval.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTapArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserTap(graph, request) },
        ),
        McpTool(
            name = "browser_swipe",
            description =
                "Swipe a browser target. from and to are CSS viewport pixels, or pass a direction. The default input auto is touch, which brings the tab to the front and uses the screen. input cdp swipes over DevTools without bringing the tab forward, but only on the tab Chrome is showing, with Chrome on screen. With Chrome hidden there is no trusted way to scroll, since Chrome holds wheel and scroll input too. Scrolling from browser_eval works but is untrusted.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSwipeArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSwipe(graph, request) },
        ),
        McpTool(
            name = "browser_gesture",
            description =
                "Play pointer paths on a browser target, in CSS viewport pixels. The default input auto is touch, which brings the tab to the front and uses the screen. input cdp plays them over DevTools without bringing the tab forward, but only on the tab Chrome is showing, with Chrome on screen.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserGestureArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserGesture(graph, request) },
        ),
        McpTool(
            name = "browser_key",
            description =
                "Press a key on a browser target. The default input auto is touch while you hold the screen, and cdp otherwise. input touch brings the tab to the front and injects a hardware key. input cdp sends it to the target in the background. Device keys such as HOME need input touch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserKeyArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserKey(graph, request) },
        ),
        McpTool(
            name = "browser_type",
            description =
                "Type text into a field in a browser target. ref or selector focuses the field first. The default input auto is touch while you hold the screen, and cdp otherwise. input touch brings the tab to the front and uses the hardware text modes. input cdp inserts the text in the background.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTextArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserText(graph, request) },
        ),
        McpTool(
            name = "browser_eval",
            description =
                "Evaluate JavaScript in an isolated world and return the result. Works in the background. awaitPromise waits for a promise. A script exception is a result field. Not retried, so a script with side effects runs at most once. Clicks and scrolls made from a script are untrusted (isTrusted false), which bot detection can see, so click with browser_tap instead.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EvalArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserEval(graph, request) },
        ),
        McpTool(
            name = "browser_console",
            description =
                "Collect log entries for timeoutMs. Entries from before the call are not returned, so start it before the action you want to watch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<ConsoleArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserConsole(graph, request) },
        ),
        McpTool(
            name = "screenshot",
            description =
                "PNG of the screen. Prefer ui_snapshot, and use this when the tree is empty or unhelpful, such as maps and images. scale is 0.1 to 1, default 0.5. Divide image coordinates by scale to get tap coordinates.",
            scope = Scope.OBSERVE,
            available = Capabilities::screenshotHelper,
            arguments = serializer<ScaleArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> screenshot(graph, request) },
        ),
        McpTool(
            name = "logcat_tail",
            description = "Recent log lines from the shell helper. lines is 1 to 400, default 80.",
            scope = Scope.OBSERVE,
            available = Capabilities::logcatAll,
            arguments = serializer<LogcatArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> logcatTail(graph, request) },
        ),
    )

internal data class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val read: (ServerServices) -> String,
)

internal fun mcpResources(scopes: Set<Scope>): List<McpResource> =
    if (Scope.OBSERVE !in scopes) emptyList() else MCP_RESOURCES

private val MCP_RESOURCES =
    listOf(
        McpResource(
            uri = "phoneapi://device/capabilities",
            name = "capabilities",
            description = "Which device features are available right now.",
            mimeType = "application/json",
            read = { services -> jsonText(services.device.capabilities()) },
        ),
        McpResource(
            uri = "phoneapi://viewer",
            name = "viewer",
            description =
                "WebCodecs page at /viewer. Pass the bearer token as access_token. That URL sets a cookie and redirects without the token. WebSocket upgrades also accept the query parameter.",
            mimeType = "text/plain",
            read = { services -> services.viewerText() },
        ),
    )
