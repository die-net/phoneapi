package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.runBlocking
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserServiceTest {
    @Test
    fun `lists a page`() {
        val body =
            """[{"id":"abc","type":"page","title":"Hi","url":"https://example.com/"},{"id":"w","type":"service_worker","url":"https://example.com/sw.js"}]"""
        val service = service(http(body), sockets = CHROME)
        val targets = runBlocking { service.targets() }
        assertEquals(1, targets.size)
        assertEquals("chrome_devtools_remote~abc", targets[0].id)
        assertEquals("Hi", targets[0].title)
        assertEquals("com.android.chrome", targets[0].packageName)
    }

    @Test
    fun `opens a tab`() {
        val service =
            service(
                switched() + serverTextFrame("""{"id":1,"result":{"targetId":"abc"}}"""),
                sockets = CHROME,
            )
        val target = runBlocking { service.openTab("https://example.com/") }
        assertEquals("chrome_devtools_remote~abc", target.id)
        assertEquals("https://example.com/", target.url)
    }

    @Test
    fun `rejects a bad url`() {
        val service = service(byteArrayOf(), sockets = CHROME)
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking { service.openTab("javascript:alert(1)") }
            }
        assertEquals(400, error.status)
    }

    @Test
    fun `reads an ax tree`() {
        val frames =
            serverTextFrame(
                """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
            ) +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"1","role":{"value":"RootWebArea"},"name":{"value":"Example"}}]}}"""
                )
        val service = service(switched() + frames, sockets = CHROME)
        val snapshot = runBlocking { service.snapshot("chrome_devtools_remote~abc") }
        assertEquals("https://example.com/", snapshot.url)
        assertEquals("Example", snapshot.title)
        assertTrue(snapshot.compact.contains("[1] RootWebArea"))
    }

    @Test
    fun `taps inside the link`() {
        val frames =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                ) +
                serverTextFrame("""{"id":4,"result":{}}""") +
                serverTextFrame(
                    """{"id":5,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                serverTextFrame(METRICS)
        var touched = Rect(0, 0, 0, 0)
        val service =
            service(
                frames,
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val result = runBlocking {
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals("inject", result.backend)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    fun `taps a css selector`() {
        val frames =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                serverTextFrame("""{"id":2,"result":{"root":{"nodeId":1}}}""") +
                serverTextFrame("""{"id":3,"result":{"nodeId":4}}""") +
                serverTextFrame("""{"id":4,"result":{}}""") +
                serverTextFrame(
                    """{"id":5,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                serverTextFrame(METRICS)
        var touched = Rect(0, 0, 0, 0)
        val service =
            service(
                frames,
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
            )
        val result = runBlocking {
            service.tap("chrome_devtools_remote~abc", BrowserTapRequest(selector = "a"))
        }
        assertTrue(result.ok)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Test
    fun `rejects a bad ref`() {
        val service = service(byteArrayOf(), sockets = CHROME)
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking { service.tap("chrome_devtools_remote~abc", BrowserTapRequest(" ")) }
            }
        assertEquals(400, error.status)
    }

    @Suppress("MissingUseCall")
    private fun service(
        response: ByteArray,
        sockets: String,
        content: suspend (String?) -> Rect = { Rect(0, 0, 1, 1) },
        touch: suspend (Rect, Boolean, InputBackend) -> ActionResult = { _, _, _ ->
            ActionResult(ok = true)
        },
    ) =
        BrowserServiceImpl(
            io = Inline,
            listSockets = { sockets },
            open = { ScriptedSocket(response) },
            websocketKey = { KEY },
            websocketMask = { byteArrayOf(0, 0, 0, 0) },
            contentBounds = content,
            touchAt = touch,
        )

    private class ScriptedSocket(response: ByteArray) : DevtoolsSocket {
        override val input: InputStream = ByteArrayInputStream(response)
        override val output: OutputStream = ByteArrayOutputStream()

        override fun close() = Unit
    }

    private fun http(body: String): ByteArray =
        asciiLines("HTTP/1.1 200 OK", "Content-Length: ${body.toByteArray().size}") +
            body.toByteArray()

    private fun switched(): ByteArray =
        asciiLines(
            "HTTP/1.1 101 Switching Protocols",
            "Sec-WebSocket-Accept: ${websocketAccept(KEY)}",
        )

    private object Inline :
        AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
        override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
            continuation
    }

    private companion object {
        const val KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        const val CHROME =
            """[{"name":"chrome_devtools_remote","pid":4,"package":"com.android.chrome"}]"""
        const val METRICS =
            """{"id":6,"result":{"cssVisualViewport":{"pageX":0,"pageY":0,"clientWidth":412.19049072265625,"clientHeight":783.2380981445312},"visualViewport":{"clientWidth":1082,"clientHeight":2056}}}"""
    }
}
