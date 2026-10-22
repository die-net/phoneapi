package net.die.phoneapi.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpScreenTest {
    @Test
    fun `every tool is classified`() {
        val known = SCREEN_TOOLS + SOMETIMES_SCREEN_TOOLS + NO_SCREEN
        val unclassified = mcpToolTemplates().map { it.name }.filter { it !in known }
        assertEquals(emptyList<String>(), unclassified)
    }

    @Test
    fun `arguments decide some tools`() {
        assertTrue(takesScreen("tap", null))
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
        assertFalse(takesScreen("device_info", null))
    }

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private companion object {
        val NO_SCREEN =
            setOf(
                "device_info",
                "logcat_tail",
                "browser_targets",
                "browser_open",
                "browser_navigate",
                "browser_close",
                "browser_find",
                "browser_eval",
                "browser_console",
            )
    }
}
