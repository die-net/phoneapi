package net.die.phoneapi.browser

import kotlinx.serialization.json.jsonObject
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AxFormatTest {
    @Test
    fun `skips ignored nodes`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Hello"},"childIds":["2","3"]},{"nodeId":"2","ignored":true,"role":{"value":"generic"},"childIds":["4"]},{"nodeId":"3","role":{"value":"button"},"name":{"value":"Go"},"properties":[{"name":"focused","value":{"type":"boolean","value":true}}]},{"nodeId":"4","role":{"value":"StaticText"},"name":{"value":"Welcome"}}]}"""
                )
                .jsonObject
        val text = formatAxTree(tree, "https://example.com/")
        assertEquals("Hello", text.title)
        assertEquals(
            listOf(
                    """# title="Hello" url=https://example.com/""",
                    """[1] RootWebArea "Hello"""",
                    """  [4] StaticText "Welcome"""",
                    """  [3] button "Go" focused""",
                )
                .joinToString("\n"),
            text.compact,
        )
    }

    @Test
    fun `joins letter nodes`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"paragraph"},"childIds":["2","3","4","5"]},{"nodeId":"2","role":{"value":"StaticText"},"name":{"value":"G"}},{"nodeId":"3","role":{"value":"StaticText"},"name":{"value":"o"}},{"nodeId":"4","role":{"value":"StaticText"},"name":{"value":" "}},{"nodeId":"5","role":{"value":"StaticText"},"name":{"value":"!"}}]}"""
                )
                .jsonObject
        val text = formatAxTree(tree)
        assertEquals(
            listOf(
                    "#",
                    """[1] paragraph""",
                    """  [2] StaticText "Go !"""",
                )
                .joinToString("\n"),
            text.compact,
        )
    }

    @Test
    fun `round trips a target id`() {
        assertEquals(
            "chrome_devtools_remote" to "abc",
            parseBrowserTargetId(browserTargetId("chrome_devtools_remote", "abc")),
        )
        assertThrows(ApiException::class.java) { parseBrowserTargetId("nope") }
    }
}
