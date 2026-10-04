package net.die.phoneapi.browser

import kotlin.random.Random
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageBoxTest {
    @Test
    fun `uses the parent box`() {
        val tree =
            ApiJson.parseToJsonElement(
                    """{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"backendDOMNodeId":1,"childIds":["2","9"]},{"nodeId":"2","role":{"value":"paragraph"},"backendDOMNodeId":12,"childIds":["3"]},{"nodeId":"3","role":{"value":"StaticText"},"backendDOMNodeId":3,"name":{"value":"T"}},{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}"""
                )
                .jsonObject
        assertEquals(12, elementBackendId(tree, "3"))
        assertEquals(9, elementBackendId(tree, "9"))
    }

    @Test
    fun `unknown ref is missing`() {
        val tree =
            ApiJson.parseToJsonElement("""{"nodes":[{"nodeId":"1","role":{"value":"link"}}]}""")
                .jsonObject
        val error = assertThrows(ApiException::class.java) { elementBackendId(tree, "4") }
        assertEquals(404, error.status)
    }

    @Test
    fun `maps a quad onto the webview`() {
        val box = screenTarget(quads(), metrics(pageY = 0.0), CONTENT)
        assertEquals(Rect(435, 1474, 647, 1541), box)
    }

    @Test
    fun `ignores document scroll`() {
        // getContentQuads is already in the visual viewport. pageY is the document scroll.
        val box = screenTarget(quads(), metrics(pageY = 400.0), CONTENT)
        assertEquals(Rect(435, 1474, 647, 1541), box)
    }

    @Test
    fun `viewport center for a cdp tap`() {
        val point = viewportTap(quads(), metrics(pageY = 400.0), humanize = false)
        assertEquals(206.09226f, point.x, 0.01f)
        assertEquals(466.48216f, point.y, 0.01f)
    }

    @Test
    fun `cdp jitter stays in the quad`() {
        val point = viewportTap(quads(), metrics(pageY = 0.0), humanize = true, random = Random(1))
        assertTrue(point.x in 165.62f..246.57f)
        assertTrue(point.y in 453.68f..479.28f)
    }

    @Test
    fun `cdp rejects an offscreen quad`() {
        val outside =
            ApiJson.parseToJsonElement("""[[900,900,980,900,980,960,900,960]]""").jsonArray
        val error =
            assertThrows(ApiException::class.java) {
                viewportTap(outside, metrics(pageY = 0.0), humanize = false)
            }
        assertEquals(409, error.status)
    }

    private fun quads() =
        ApiJson.parseToJsonElement(
                """[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]"""
            )
            .jsonArray

    private fun metrics(pageY: Double) =
        ApiJson.parseToJsonElement(
                """{"cssVisualViewport":{"pageX":0,"pageY":$pageY,"clientWidth":412.19049072265625,"clientHeight":783.2380981445312},"visualViewport":{"clientWidth":1082,"clientHeight":2056}}"""
            )
            .jsonObject

    private companion object {
        val CONTENT = Rect(0, 283, 1080, 2339)
    }
}
