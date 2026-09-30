package net.die.phoneapi.mcp

import net.die.phoneapi.model.Capabilities
import net.die.phoneapi.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpCatalogTest {
    @Test
    fun `observe hides control tools`() {
        val names = visibleMcpTools(setOf(Scope.OBSERVE), allOn()).map { it.name }
        assertTrue("device_info" in names)
        assertTrue("ui_snapshot" in names)
        assertTrue("screenshot" in names)
        assertFalse("tap" in names)
        assertFalse("browser_targets" in names)
        assertFalse("app_clear" in names)
    }

    @Test
    fun `hides browser without cdp`() {
        val caps = allOn().toMutableMap()
        caps[Capabilities.BROWSER_CDP] = false
        val names = visibleMcpTools(setOf(Scope.OBSERVE, Scope.BROWSER), caps).map { it.name }
        assertFalse(names.any { it.startsWith("browser_") })
    }

    @Test
    fun `hides screenshot and logcat`() {
        val caps = allOn().toMutableMap()
        caps[Capabilities.SCREENSHOT_A11Y] = false
        caps[Capabilities.SCREENSHOT_HELPER] = false
        caps[Capabilities.LOGCAT_ALL] = false
        caps[Capabilities.APPS_MANAGE] = false
        val names = visibleMcpTools(Scope.entries.toSet(), caps).map { it.name }
        assertFalse("screenshot" in names)
        assertFalse("logcat_tail" in names)
        assertFalse("app_stop" in names)
        assertTrue("app_launch" in names)
        assertTrue("browser_eval" in names)
    }

    @Test
    fun `resources need observe`() {
        assertTrue(mcpResources(setOf(Scope.OBSERVE)).any { it.uri.endsWith("capabilities") })
        assertEquals(0, mcpResources(setOf(Scope.CONTROL)).size)
    }

    private fun allOn(): Map<String, Boolean> =
        listOf(
                Capabilities.UI_SNAPSHOT,
                Capabilities.INPUT_A11Y,
                Capabilities.INPUT_INJECT,
                Capabilities.TEXT_IME,
                Capabilities.TEXT_KEYEVENT,
                Capabilities.SCREENSHOT_A11Y,
                Capabilities.SCREENSHOT_HELPER,
                Capabilities.APPS_MANAGE,
                Capabilities.LOGCAT_ALL,
                Capabilities.BROWSER_CDP,
            )
            .associateWith { true }
}
