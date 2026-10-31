package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // The lease's cleanup scope needs no real dispatcher here.
class McpScreenTest {
    @Test
    fun `notes reach any call`() = runBlocking {
        val lease =
            ScreenLease(
                CoroutineScope(Dispatchers.Unconfined),
                cleanup = { emptyList() },
                lockedNow = { true },
            )
        val holder = Holder.Session("a")
        lease.addNote(holder, "the screen went elsewhere")
        val result =
            leased(lease, holder, needed = false) {
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }
        assertEquals(
            "# note: the screen went elsewhere",
            (result.content.first() as TextContent).text,
        )
        assertTrue(lease.takeNotes(holder).isEmpty())
    }

    @Test
    fun `every tool is classified`() {
        val known = SCREEN_TOOLS + SOMETIMES_SCREEN_TOOLS + NO_SCREEN
        val unclassified = MCP_TOOLS.map { it.name }.filter { it !in known }
        assertEquals(emptyList<String>(), unclassified)
    }

    @Test
    fun `arguments decide some tools`() {
        assertTrue(takesScreen("tap", null))
        assertTrue(takesScreen("clipboard_read", null))
        assertFalse(takesScreen("browser_read", null))
        assertTrue(takesScreen("browser_read", args("""{"show":true}""")))
        assertTrue(takesScreen("browser_tap", null))
        assertTrue(takesScreen("browser_tap", args("""{"input":"cdp"}""")))
        assertFalse(takesScreen("browser_tap", args("""{"input":"cdp-mouse"}""")))
        assertFalse(takesScreen("browser_type", args("""{"input":"CDP"}""")))
        assertTrue(takesScreen("browser_key", null))
        assertFalse(takesScreen("wait_for", args("""{"all":[{"type":"browser.url"}]}""")))
        assertTrue(
            takesScreen(
                "wait_for",
                args("""{"all":[{"type":"browser.url"}],"any":[{"type":"ime"}]}"""),
            )
        )
        val browserWait = """"all":[{"type":"browser.url"}]"""
        assertTrue(takesScreen("wait_for", args("""{$browserWait,"snapshot":true}""")))
        assertTrue(takesScreen("wait_for", args("""{$browserWait,"screenshot":true}""")))
        assertFalse(takesScreen("wait_for", args("""{$browserWait,"snapshot":false}""")))
        assertFalse(takesScreen("device_info", null))
        assertFalse(takesScreen("browser_open", args("""{"url":"https://example.com/"}""")))
        assertTrue(takesScreen("browser_open", args("""{"incognito":true}""")))
    }

    @Test
    fun `some tools open tabs`() {
        assertTrue(opensTab("browser_open", null))
        assertTrue(opensTab("browser_read", args("""{"url":"https://example.com/"}""")))
        assertTrue(opensTab("browser_snapshot", args("""{"url":"https://example.com/"}""")))
        assertFalse(opensTab("browser_read", args("""{"target":"t"}""")))
        assertFalse(opensTab("browser_navigate", args("""{"url":"https://example.com/"}""")))
    }

    @Test
    fun `person waits skip screen`() {
        assertFalse(takesScreen("wait_for", args("""{"all":[{"type":"screen","person":true}]}""")))
        assertTrue(takesScreen("wait_for", args("""{"all":[{"type":"screen","on":true}]}""")))
        val both = """{"any":[{"type":"screen","on":true,"person":false}]}"""
        assertTrue(takesScreen("wait_for", args(both)))
        assertTrue(takesScreen("wait_for", args("""{"all":[{"type":"nonsense"}]}""")))
    }

    @Test
    fun `auto input follows the holder`() {
        assertEquals("cdp-mouse", resolved("browser_tap", "{}", holds = false))
        assertEquals("touch", resolved("browser_tap", """{"input":"auto"}""", holds = true))
        assertEquals("cdp", resolved("browser_type", "{}", holds = false))
        assertEquals("touch", resolved("browser_swipe", "{}", holds = false))
        assertEquals("cdp", resolved("browser_tap", """{"input":"cdp"}""", holds = true))
        val untouched = request("browser_read", "{}")
        assertEquals(untouched, resolveInput("browser_read", untouched, false, ALL))
        val rest = resolveInput("browser_tap", request("browser_tap", "{}"), false, ALL)
        assertFalse(takesScreen("browser_tap", rest.params.arguments))
    }

    @Test
    fun `touch keys need control`() {
        val browser = setOf(Scope.BROWSER)
        assertEquals("touch", resolved("browser_key", "{}", holds = true))
        assertEquals("cdp", resolved("browser_key", "{}", holds = true, scopes = browser))
        assertEquals("touch", resolved("browser_type", "{}", holds = true, scopes = browser))
        assertEquals("touch", resolved("browser_tap", "{}", holds = true, scopes = browser))
        assertEquals(
            "touch",
            resolved("browser_key", """{"input":"touch"}""", holds = true, scopes = browser),
        )
    }

    private fun resolved(
        tool: String,
        json: String,
        holds: Boolean,
        scopes: Set<Scope> = ALL,
    ): String? =
        resolveInput(tool, request(tool, json), holds, scopes)
            .params
            .arguments
            ?.get("input")
            ?.jsonPrimitive
            ?.content

    private fun request(tool: String, json: String) =
        CallToolRequest(CallToolRequestParams(name = tool, arguments = args(json)))

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private companion object {
        val NO_SCREEN =
            setOf(
                "device_info",
                "screen_release",
                "session_start",
                "session_end",
                "logcat_tail",
                "browser_targets",
                "browser_navigate",
                "browser_close",
                "browser_find",
                "browser_eval",
                "browser_console",
                "browser_capture",
            )
        val ALL = Scope.entries.toSet()
    }
}
