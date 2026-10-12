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
            name = "ui_snapshot",
            description =
                "Compact outline of the foreground app's accessibility tree. Prefer this over screenshot. Take a new one after each action. Canvas content such as maps shows little, so use screenshot there. Right after an action the tree can still be the old screen: wait_for first.",
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
                "Open a tab and return its target. With Chrome hidden, the tab opens in the background and the screen is untouched. With Chrome on screen, Chrome switches to the new tab. url must be http(s) or about:blank. A page that hands off to an installed app loads as a website in the tab. Follow with wait_for on browser.url and browser.settled.",
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
                "Compact outline of a browser target. Works in the background. Heavy pages give a large outline, so browser_eval on document.body.innerText can be smaller.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserSnapshot(graph, request) },
        ),
        McpTool(
            name = "browser_tap",
            description =
                "Tap a snapshot ref or a CSS selector in a browser target. Default input touch brings the tab to the front and uses the screen. input cdp sends touch events to the target, even a background tab, but needs Chrome on screen. input cdp-mouse clicks with a mouse, which works with Chrome hidden and the screen off, but the page sees a mouse rather than a finger. All three are trusted input, unlike a click from browser_eval.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTapArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserTap(graph, request) },
        ),
        McpTool(
            name = "browser_swipe",
            description =
                "Swipe a browser target. from and to are CSS viewport pixels, or pass a direction. Default input touch brings the tab to the front and uses the screen. input cdp swipes the target, even a background tab, but needs Chrome on screen. With Chrome hidden there is no trusted way to scroll, since Chrome holds wheel and scroll input too. Scrolling from browser_eval works but is untrusted.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSwipeArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSwipe(graph, request) },
        ),
        McpTool(
            name = "browser_gesture",
            description =
                "Play pointer paths on a browser target, in CSS viewport pixels. Default input touch brings the tab to the front and uses the screen. input cdp plays them on the target, even a background tab, but needs Chrome on screen.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserGestureArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserGesture(graph, request) },
        ),
        McpTool(
            name = "browser_key",
            description =
                "Press a key on a browser target. Default input touch brings the tab to the front and injects a hardware key. input cdp sends it to the target in the background. Device keys such as HOME need input touch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserKeyArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserKey(graph, request) },
        ),
        McpTool(
            name = "browser_text",
            description =
                "Type into a browser target. Default input touch brings the tab to the front and uses the hardware text modes. input cdp inserts the text in the background.",
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
