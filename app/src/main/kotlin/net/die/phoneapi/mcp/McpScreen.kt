package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserInputKind

/** Tools that always take the screen: they act on it or show what is on it. */
internal val SCREEN_TOOLS =
    setOf(
        "ui_snapshot",
        "ui_find",
        "ui_act",
        "tap",
        "swipe",
        "type_text",
        "press_key",
        "keyboard_hide",
        "keyboard_show",
        "app_launch",
        "app_stop",
        "app_clear",
        "open_intent",
        "device_wake",
        "device_unlock",
        "device_lock",
        "device_orientation",
        "screenshot",
    )

/** Tools whose arguments decide, in [takesScreen]. */
internal val SOMETIMES_SCREEN_TOOLS =
    setOf(
        "wait_for",
        "browser_tap",
        "browser_swipe",
        "browser_gesture",
        "browser_key",
        "browser_type",
        "browser_read",
        "browser_snapshot",
    )

/**
 * Whether a call to [tool] with [args] takes the screen, as docs/sharing.md lists. Reading and cdp
 * input on a tab need no screen; touch input brings the tab forward, and Chrome only takes cdp
 * pointer input on the tab it is showing.
 */
internal fun takesScreen(tool: String, args: JsonObject?): Boolean =
    when (tool) {
        in SCREEN_TOOLS -> true
        "wait_for" -> conditionTypes(args).any { !it.startsWith("browser.") }
        "browser_tap",
        "browser_swipe",
        "browser_gesture" -> input(args) !in setOf("cdp-mouse")
        "browser_key",
        "browser_type" -> input(args) in setOf("touch", "auto")
        "browser_read",
        "browser_snapshot" -> (args?.get("show") as? JsonPrimitive)?.booleanOrNull == true
        else -> false
    }

/**
 * Tools that, without the screen, could still make a page launch an app, so the foreground watch
 * watches the top task around them.
 */
internal val LAUNCHING_TOOLS =
    setOf(
        "browser_open",
        "browser_navigate",
        "browser_eval",
        "browser_tap",
        "browser_swipe",
        "browser_gesture",
        "browser_key",
        "browser_type",
    )

/** The browser input tools, and the kind of input each sends. */
private val INPUT_KINDS =
    mapOf(
        "browser_tap" to BrowserInputKind.TAP,
        "browser_swipe" to BrowserInputKind.POINTER,
        "browser_gesture" to BrowserInputKind.POINTER,
        "browser_key" to BrowserInputKind.KEYS,
        "browser_type" to BrowserInputKind.KEYS,
    )

/**
 * Replaces `input` `auto`, or no input, with what it means for a caller that [holdsScreen] or not,
 * so [takesScreen] and the tool see the input that will be used.
 */
internal fun resolveInput(
    tool: String,
    request: CallToolRequest,
    holdsScreen: Boolean,
): CallToolRequest {
    val kind = INPUT_KINDS[tool] ?: return request
    val args = request.params.arguments ?: JsonObject(emptyMap())
    val asked = input(args)
    if (asked != "auto") return request
    val resolved = BrowserInput.AUTO.resolved(kind, holdsScreen)
    val wire = ApiJson.encodeToJsonElement(BrowserInput.serializer(), resolved)
    val params = request.params.copy(arguments = JsonObject(args + ("input" to wire)))
    return request.copy(params = params)
}

private fun input(args: JsonObject?): String =
    (args?.get("input") as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "auto"

private fun conditionTypes(args: JsonObject?): List<String> =
    listOf("all", "any").flatMap { key ->
        (args?.get(key) as? JsonArray).orEmpty().mapNotNull { condition ->
            (condition.jsonObject["type"] as? JsonPrimitive)?.contentOrNull
        }
    }

/** `took_screen` as a tool error, like the lease's own errors. */
internal suspend fun tookScreenError(call: suspend () -> CallToolResult): CallToolResult =
    try {
        call()
    } catch (e: ApiException) {
        if (e.error != "took_screen") throw e
        errorResult(e)
    }

private fun errorResult(e: ApiException) =
    CallToolResult(
        content = listOf(TextContent(text = "${e.error}: ${e.message.orEmpty()}")),
        isError = true,
    )

/**
 * Runs [call] holding the screen for [holder] when [needed], and puts the holder's notes first in
 * the result. `screen_busy` and `screen_taken` come back as tool errors.
 */
internal suspend fun leased(
    lease: ScreenLease,
    holder: Holder,
    needed: Boolean,
    call: suspend () -> CallToolResult,
): CallToolResult {
    if (!needed) return call()
    val screened =
        try {
            lease.withScreen(holder) { call() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return errorResult(e)
        }
    val result = screened.value
    if (screened.notes.isEmpty()) return result
    val notes = TextContent(text = screened.notes.joinToString("\n") { "# note: $it" })
    return CallToolResult(
        content = listOf(notes) + result.content,
        isError = result.isError,
        structuredContent = result.structuredContent,
    )
}
