package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.die.phoneapi.AppGraph
import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.Scope

internal data class McpTool(
    val name: String,
    val description: String,
    val scope: Scope,
    val available: (Map<String, Boolean>) -> Boolean,
    val schema: ToolSchema,
    val annotations: ToolAnnotations? = null,
    val call: suspend (AppGraph, Set<Scope>, CallToolRequest) -> CallToolResult,
)

internal fun visibleMcpTools(
    scopes: Set<Scope>,
    capabilities: Map<String, Boolean>,
): List<McpTool> = MCP_TOOLS.filter { it.scope in scopes && it.available(capabilities) }

private val readOnly = ToolAnnotations(readOnlyHint = true, openWorldHint = false)

private val changes =
    ToolAnnotations(readOnlyHint = false, destructiveHint = false, openWorldHint = false)

private val destructive =
    ToolAnnotations(readOnlyHint = false, destructiveHint = true, openWorldHint = false)

private val always: (Map<String, Boolean>) -> Boolean = { true }

private fun has(key: String): (Map<String, Boolean>) -> Boolean = { it[key] == true }

private fun hasAny(vararg keys: String): (Map<String, Boolean>) -> Boolean = { caps ->
    keys.any { caps[it] == true }
}

private fun schema(vararg fields: Field, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(
        properties =
            buildJsonObject {
                fields.forEach { field ->
                    putJsonObject(field.name) {
                        put("type", field.type)
                        field.description?.let { put("description", it) }
                    }
                }
            },
        required = required,
    )

private data class Field(val name: String, val type: String, val description: String? = null)

private val MCP_TOOLS: List<McpTool> =
    listOf(
        McpTool(
            name = "device_info",
            description =
                "Manufacturer, SDK, display, lock state, helper status, and capabilities.",
            scope = Scope.OBSERVE,
            available = always,
            schema = schema(),
            annotations = readOnly,
            call = { graph, _, _ -> deviceInfo(graph) },
        ),
        McpTool(
            name = "ui_snapshot",
            description =
                "Compact accessibility outline of the current UI. Prefer this over screenshot.",
            scope = Scope.OBSERVE,
            available = has(Capabilities.UI_SNAPSHOT),
            schema =
                schema(
                    Field("window", "string", "Window id, such as 3 or w3"),
                    Field("maxDepth", "integer"),
                    Field("includeInvisible", "boolean"),
                    Field("all", "boolean", "Include system windows"),
                    Field("autoWake", "boolean"),
                ),
            annotations = readOnly,
            call = { graph, _, request -> uiSnapshot(graph, request) },
        ),
        McpTool(
            name = "ui_find",
            description = "Find nodes. selector matches text, id, role, and the other node fields.",
            scope = Scope.OBSERVE,
            available = has(Capabilities.UI_SNAPSHOT),
            schema =
                schema(
                    Field("selector", "object"),
                    Field("limit", "integer"),
                    Field("includeInvisible", "boolean"),
                ),
            annotations = readOnly,
            call = { graph, _, request -> uiFind(graph, request) },
        ),
        McpTool(
            name = "ui_act",
            description =
                "Act on a snapshot ref. action is click, longClick, setText, scrollForward, and the other node actions. mode semantic skips real touch.",
            scope = Scope.CONTROL,
            available = has(Capabilities.UI_SNAPSHOT),
            schema =
                schema(
                    Field("ref", "string"),
                    Field("action", "string"),
                    Field("mode", "string", "real or semantic"),
                    Field("text", "string"),
                    Field("force", "boolean"),
                ),
            annotations = changes,
            call = { graph, _, request -> uiAct(graph, request) },
        ),
        McpTool(
            name = "tap",
            description = "Tap a point or a node selector. Humanized by default.",
            scope = Scope.CONTROL,
            available = hasAny(Capabilities.INPUT_A11Y, Capabilities.INPUT_INJECT),
            schema =
                schema(
                    Field("x", "number"),
                    Field("y", "number"),
                    Field("selector", "object"),
                    Field("humanize", "boolean"),
                ),
            annotations = changes,
            call = { graph, _, request -> tap(graph, request) },
        ),
        McpTool(
            name = "swipe",
            description =
                "Swipe from/to, or inside a selector in a direction (up, down, left, right).",
            scope = Scope.CONTROL,
            available = hasAny(Capabilities.INPUT_A11Y, Capabilities.INPUT_INJECT),
            schema =
                schema(
                    Field("from", "object"),
                    Field("to", "object"),
                    Field("selector", "object"),
                    Field("direction", "string"),
                    Field("humanize", "boolean"),
                ),
            annotations = changes,
            call = { graph, _, request -> swipe(graph, request) },
        ),
        McpTool(
            name = "type_text",
            description =
                "Type into the focused field, or into selector first. mode auto, keyboard, ime, keyevent, or setText.",
            scope = Scope.CONTROL,
            available =
                hasAny(Capabilities.TEXT_IME, Capabilities.TEXT_KEYEVENT, Capabilities.INPUT_A11Y),
            schema =
                schema(
                    Field("text", "string"),
                    Field("selector", "object"),
                    Field("mode", "string"),
                    Field("clear", "boolean"),
                    Field("submit", "boolean"),
                ),
            annotations = changes,
            call = { graph, _, request -> typeText(graph, request) },
        ),
        McpTool(
            name = "press_key",
            description = "Press BACK, HOME, ENTER, DEL, or any KEYCODE_* name.",
            scope = Scope.CONTROL,
            available = hasAny(Capabilities.INPUT_A11Y, Capabilities.TEXT_KEYEVENT),
            schema =
                schema(
                    Field("key", "string"),
                    Field("longPress", "boolean"),
                    required = listOf("key"),
                ),
            annotations = changes,
            call = { graph, _, request -> pressKey(graph, request) },
        ),
        McpTool(
            name = "wait_for",
            description =
                "Wait until all or any conditions match. A condition's type is node, window, ime, idle, screen, keyguard, or browser.*. Browser conditions need the browser scope.",
            scope = Scope.OBSERVE,
            available = always,
            schema =
                schema(
                    Field("all", "array"),
                    Field("any", "array"),
                    Field("timeoutMs", "integer"),
                    Field("settleMs", "integer"),
                    Field("snapshot", "boolean"),
                ),
            annotations = readOnly,
            call = { graph, scopes, request -> waitFor(graph, scopes, request) },
        ),
        McpTool(
            name = "keyboard_hide",
            description = "Hide the soft keyboard.",
            scope = Scope.CONTROL,
            available = has(Capabilities.INPUT_A11Y),
            schema = schema(),
            annotations = changes,
            call = { graph, _, _ -> keyboardHide(graph) },
        ),
        McpTool(
            name = "app_launch",
            description = "Launch an app by package name.",
            scope = Scope.CONTROL,
            available = always,
            schema =
                schema(
                    Field("package", "string"),
                    Field("fresh", "boolean"),
                    Field("activity", "string"),
                    Field("wait", "boolean"),
                    required = listOf("package"),
                ),
            annotations = changes,
            call = { graph, _, request -> appLaunch(graph, request) },
        ),
        McpTool(
            name = "app_stop",
            description = "Force-stop an app. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = has(Capabilities.APPS_MANAGE),
            schema = schema(Field("package", "string"), required = listOf("package")),
            annotations = destructive,
            call = { graph, _, request -> appStop(graph, request) },
        ),
        McpTool(
            name = "app_clear",
            description = "Clear an app's data. Requires the shell helper.",
            scope = Scope.CONTROL,
            available = has(Capabilities.APPS_MANAGE),
            schema = schema(Field("package", "string"), required = listOf("package")),
            annotations = destructive,
            call = { graph, _, request -> appClear(graph, request) },
        ),
        McpTool(
            name = "open_intent",
            description = "Start an intent. data is a URI. package and component are optional.",
            scope = Scope.CONTROL,
            available = always,
            schema =
                schema(
                    Field("action", "string"),
                    Field("data", "string"),
                    Field("package", "string"),
                    Field("component", "string"),
                ),
            annotations = changes,
            call = { graph, _, request -> openIntent(graph, request) },
        ),
        McpTool(
            name = "device_wake",
            description = "Turn the screen on.",
            scope = Scope.CONTROL,
            available = always,
            schema = schema(),
            annotations = changes,
            call = { graph, _, _ -> deviceWake(graph) },
        ),
        McpTool(
            name = "device_unlock",
            description = "Dismiss the keyguard. A secure lock uses the stored PIN.",
            scope = Scope.CONTROL,
            available = always,
            schema = schema(),
            annotations = changes,
            call = { graph, _, _ -> deviceUnlock(graph) },
        ),
        McpTool(
            name = "device_lock",
            description = "Lock the device.",
            scope = Scope.CONTROL,
            available = always,
            schema = schema(),
            annotations = destructive,
            call = { graph, _, _ -> deviceLock(graph) },
        ),
        McpTool(
            name = "browser_targets",
            description = "List Chrome tabs and debuggable WebViews.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema = schema(),
            annotations = readOnly,
            call = { graph, _, _ -> browserTargets(graph) },
        ),
        McpTool(
            name = "browser_open",
            description = "Open a tab. url must be http(s) or about:blank.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema = schema(Field("url", "string"), required = listOf("url")),
            annotations = changes,
            call = { graph, _, request -> browserOpen(graph, request) },
        ),
        McpTool(
            name = "browser_navigate",
            description = "Navigate an existing target. target is the id from browser_targets.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema =
                schema(
                    Field("target", "string"),
                    Field("url", "string"),
                    required = listOf("target", "url"),
                ),
            annotations = changes,
            call = { graph, _, request -> browserNavigate(graph, request) },
        ),
        McpTool(
            name = "browser_snapshot",
            description = "Compact accessibility outline of a browser target.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema = schema(Field("target", "string"), required = listOf("target")),
            annotations = readOnly,
            call = { graph, _, request -> browserSnapshot(graph, request) },
        ),
        McpTool(
            name = "browser_tap",
            description =
                "Tap a snapshot ref or a CSS selector in a browser target, with a real touch.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema =
                schema(
                    Field("target", "string"),
                    Field("ref", "string"),
                    Field("selector", "string"),
                    Field("humanize", "boolean"),
                    required = listOf("target"),
                ),
            annotations = changes,
            call = { graph, _, request -> browserTap(graph, request) },
        ),
        McpTool(
            name = "browser_eval",
            description =
                "Evaluate JavaScript in an isolated world. A script exception is a result field.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema =
                schema(
                    Field("target", "string"),
                    Field("expression", "string"),
                    Field("awaitPromise", "boolean"),
                    required = listOf("target", "expression"),
                ),
            annotations = changes,
            call = { graph, _, request -> browserEval(graph, request) },
        ),
        McpTool(
            name = "browser_console",
            description =
                "Collect log entries for timeoutMs. Entries from before the call are not replayed.",
            scope = Scope.BROWSER,
            available = has(Capabilities.BROWSER_CDP),
            schema =
                schema(
                    Field("target", "string"),
                    Field("timeoutMs", "integer"),
                    required = listOf("target"),
                ),
            annotations = readOnly,
            call = { graph, _, request -> browserConsole(graph, request) },
        ),
        McpTool(
            name = "screenshot",
            description =
                "Last resort. PNG of the screen. Prefer ui_snapshot. scale is 0.1 to 1, default 0.5.",
            scope = Scope.OBSERVE,
            available = hasAny(Capabilities.SCREENSHOT_A11Y, Capabilities.SCREENSHOT_HELPER),
            schema = schema(Field("scale", "number")),
            annotations = readOnly,
            call = { graph, _, request -> screenshot(graph, request) },
        ),
        McpTool(
            name = "logcat_tail",
            description = "Recent log lines from the shell helper. lines is 1 to 400, default 80.",
            scope = Scope.OBSERVE,
            available = has(Capabilities.LOGCAT_ALL),
            schema = schema(Field("lines", "integer"), Field("tag", "string")),
            annotations = readOnly,
            call = { graph, _, request -> logcatTail(graph, request) },
        ),
    )

internal data class McpResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val read: (AppGraph) -> String,
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
            read = { graph -> jsonText(graph.deviceInfo.capabilities()) },
        ),
        McpResource(
            uri = "phoneapi://viewer",
            name = "viewer",
            description = "WebCodecs page at /viewer. Pass the bearer token as access_token.",
            mimeType = "text/plain",
            read = { graph -> viewerText(graph) },
        ),
    )

internal fun viewerText(graph: AppGraph): String {
    val port = graph.settings.current.port
    val host = graph.network.lanAddress.value?.hostAddress ?: "127.0.0.1"
    return "https://$host:$port/viewer?access_token=TOKEN\nReplace TOKEN with this device's bearer token."
}
