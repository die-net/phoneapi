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
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserInput
import net.die.phoneapi.model.BrowserInputKind
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.wait.usesScreen

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
        "clipboard_read",
        "clipboard_write",
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
        "browser_open",
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
        "wait_for" ->
            conditionsUseScreen(args) || flag(args, "snapshot") || flag(args, "screenshot")
        "browser_tap",
        "browser_swipe",
        "browser_gesture" -> input(args) !in setOf("cdp-mouse")
        "browser_key",
        "browser_type" -> input(args) in setOf("touch", "auto")
        "browser_read",
        "browser_snapshot" -> flag(args, "show")
        "browser_open" -> flag(args, "incognito")
        else -> false
    }

private fun flag(args: JsonObject?, name: String): Boolean =
    (args?.get(name) as? JsonPrimitive)?.booleanOrNull == true

/**
 * Tools that need the screen only while they run, so a session that did not have it before gives it
 * back after.
 */
internal val BRIEF_SCREEN_TOOLS = setOf("browser_open")

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

/** Tools that can make Chrome open a tab, which then belongs to the caller. */
internal val CLAIMING_TOOLS = setOf("open_intent", "app_launch")

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

/**
 * Whether any condition in wait_for's [args] uses the screen. One that does not parse counts as
 * using it, as an unknown type would; the call itself then reports the error.
 */
private fun conditionsUseScreen(args: JsonObject?): Boolean =
    listOf("all", "any").any { key ->
        (args?.get(key) as? JsonArray).orEmpty().any { condition ->
            runCatching { ApiJson.decodeFromJsonElement(WaitCondition.serializer(), condition) }
                .map(::usesScreen)
                .getOrDefault(true)
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
 * the result. With [giveBack], a holder that did not have the screen lets it go after the call.
 * `screen_busy` and `screen_taken` come back as tool errors.
 */
internal suspend fun leased(
    lease: ScreenLease,
    holder: Holder,
    needed: Boolean,
    giveBack: Boolean = false,
    call: suspend () -> CallToolResult,
): CallToolResult {
    // A call that needs no screen still shows the notes waiting for its session, such as the
    // screen having gone to another client, so they are not held until its next screen call.
    if (!needed) return withNotes(call(), lease.takeNotes(holder))
    val screened =
        try {
            lease.withScreen(holder, giveBack) { call() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return errorResult(e)
        }
    return withNotes(screened.value, screened.notes)
}

/** [result] with [notes] first, as `# note:` lines. */
private fun withNotes(result: CallToolResult, notes: List<String>): CallToolResult {
    if (notes.isEmpty()) return result
    val lines = TextContent(text = notes.joinToString("\n") { "# note: $it" })
    return CallToolResult(
        content = listOf(lines) + result.content,
        isError = result.isError,
        structuredContent = result.structuredContent,
    )
}
