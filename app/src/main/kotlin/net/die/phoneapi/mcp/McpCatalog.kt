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
                "Manufacturer, SDK, display, lock state, helper status, and capabilities.",
            scope = Scope.OBSERVE,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> deviceInfo(graph) },
        ),
        McpTool(
            name = "ui_snapshot",
            description = "Compact outline of the current UI. Prefer this over screenshot.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<SnapshotArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> uiSnapshot(graph, request) },
        ),
        McpTool(
            name = "ui_find",
            description = "Find nodes. selector matches text, id, role, and the other node fields.",
            scope = Scope.OBSERVE,
            available = Capabilities::uiSnapshot,
            arguments = serializer<FindRequest>(),
            annotations = readOnly,
            call = { graph, _, request -> uiFind(graph, request) },
        ),
        McpTool(
            name = "ui_act",
            description =
                "Act on a snapshot ref. action is click, longClick, setText, scrollForward, and the other node actions. mode semantic skips real touch.",
            scope = Scope.CONTROL,
            available = Capabilities::uiSnapshot,
            arguments = serializer<NodeActArgs>(),
            annotations = changes,
            call = { graph, _, request -> uiAct(graph, request) },
        ),
        McpTool(
            name = "tap",
            description = "Tap a point or a node selector. Humanized by default.",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<TapRequest>(),
            annotations = changes,
            call = { graph, _, request -> tap(graph, request) },
        ),
        McpTool(
            name = "swipe",
            description =
                "Swipe from/to, or inside a selector in a direction (up, down, left, right).",
            scope = Scope.CONTROL,
            available = Capabilities::inputInject,
            arguments = serializer<SwipeRequest>(),
            annotations = changes,
            call = { graph, _, request -> swipe(graph, request) },
        ),
        McpTool(
            name = "type_text",
            description =
                "Type into the focused field, or into selector first. mode auto, keyboard, keyevent, or setText.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<TextRequest>(),
            annotations = changes,
            call = { graph, _, request -> typeText(graph, request) },
        ),
        McpTool(
            name = "press_key",
            description = "Press BACK, HOME, ENTER, DEL, or any KEYCODE_* name.",
            scope = Scope.CONTROL,
            available = Capabilities::textKeyevent,
            arguments = serializer<KeyRequest>(),
            annotations = changes,
            call = { graph, _, request -> pressKey(graph, request) },
        ),
        McpTool(
            name = "wait_for",
            description =
                "Wait until all or any conditions match. A condition's type is node, window, ime, idle, screen, keyguard, or browser.*. Browser conditions need the browser scope.",
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
            description = "Launch an app by package name.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<PackageArgs>(),
            annotations = changes,
            call = { graph, _, request -> appLaunch(graph, request) },
        ),
        McpTool(
            name = "app_stop",
            description = "Force-stop an app. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appStop(graph, request) },
        ),
        McpTool(
            name = "app_clear",
            description = "Clear an app's data. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<PackageNameArgs>(),
            annotations = destructive,
            call = { graph, _, request -> appClear(graph, request) },
        ),
        McpTool(
            name = "open_intent",
            description =
                "Start an intent. data is a URI. package and component are optional. Requires " +
                    "the shell helper.",
            scope = Scope.CONTROL,
            available = Capabilities::appsManage,
            arguments = serializer<IntentRequest>(),
            annotations = changes,
            call = { graph, _, request -> openIntent(graph, request) },
        ),
        McpTool(
            name = "device_wake",
            description = "Turn the screen on.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceWake(graph) },
        ),
        McpTool(
            name = "device_unlock",
            description = "Dismiss the keyguard. A secure lock uses the stored PIN.",
            scope = Scope.CONTROL,
            available = always,
            arguments = serializer<EmptyArgs>(),
            annotations = changes,
            call = { graph, _, _ -> deviceUnlock(graph) },
        ),
        McpTool(
            name = "device_lock",
            description = "Lock the device.",
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
                "List Chrome tabs and debuggable WebViews. Chrome must be running with USB debugging on. Android 11+ needs PhoneAPI paired with wireless debugging. Android 10 needs the USB DevTools forward.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EmptyArgs>(),
            annotations = readOnly,
            call = { graph, _, _ -> browserTargets(graph) },
        ),
        McpTool(
            name = "browser_open",
            description = "Open a tab. url must be http(s) or about:blank.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<UrlArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserOpen(graph, request) },
        ),
        McpTool(
            name = "browser_navigate",
            description = "Navigate an existing target. target is the id from browser_targets.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetUrlArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserNavigate(graph, request) },
        ),
        McpTool(
            name = "browser_snapshot",
            description = "Compact outline of a browser target.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<TargetArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserSnapshot(graph, request) },
        ),
        McpTool(
            name = "browser_tap",
            description =
                "Tap a snapshot ref or a CSS selector in a browser target. input touch (default) brings the tab forward and injects a touchscreen event. input cdp sends the tap to that target, including a background tab.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTapArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserTap(graph, request) },
        ),
        McpTool(
            name = "browser_swipe",
            description =
                "Swipe a browser target. from and to are CSS viewport pixels, or pass a direction. input touch (default) brings the tab forward and injects a touchscreen swipe. input cdp sends it to that target, including a background tab.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserSwipeArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserSwipe(graph, request) },
        ),
        McpTool(
            name = "browser_gesture",
            description =
                "Play pointer paths on a browser target, in CSS viewport pixels. input touch (default) or cdp.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserGestureArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserGesture(graph, request) },
        ),
        McpTool(
            name = "browser_key",
            description =
                "Press a key on a browser target. input touch (default) injects a hardware key after bringing the tab forward. input cdp sends it to that target. Device keys such as HOME need input touch.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserKeyArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserKey(graph, request) },
        ),
        McpTool(
            name = "browser_text",
            description =
                "Type into a browser target. input touch (default) uses the hardware text modes after bringing the tab forward. input cdp inserts the text, including in a background tab.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<BrowserTextArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserText(graph, request) },
        ),
        McpTool(
            name = "browser_eval",
            description =
                "Evaluate JavaScript in an isolated world. A script exception is a result field.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<EvalArgs>(),
            annotations = changes,
            call = { graph, _, request -> browserEval(graph, request) },
        ),
        McpTool(
            name = "browser_console",
            description =
                "Collect log entries for timeoutMs. Entries from before the call are not replayed.",
            scope = Scope.BROWSER,
            available = Capabilities::browserCdp,
            arguments = serializer<ConsoleArgs>(),
            annotations = readOnly,
            call = { graph, _, request -> browserConsole(graph, request) },
        ),
        McpTool(
            name = "screenshot",
            description =
                "Last resort. PNG of the screen. Prefer ui_snapshot. scale is 0.1 to 1, default 0.5.",
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
