package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
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

@Suppress("InjectDispatcher") // Scripted sockets are read on a real dispatcher.
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
                serverTextFrame(metrics(6))
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
                serverTextFrame(metrics(6))
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

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `reuses a snapshot ref`() {
        val snapshot =
            switched() +
                serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                )
        val tap =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(quad(3)) +
                serverTextFrame(metrics(4))
        var opens = 0
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { _, _, _ -> ActionResult(ok = true, backend = "inject") },
                open = {
                    opens++
                    ScriptedSocket(if (opens == 1) snapshot else tap)
                },
            )
        val result = runBlocking {
            service.snapshot("chrome_devtools_remote~abc")
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals(2, opens)
    }

    @Test
    @Suppress("MissingUseCall") // The service closes each socket it opens.
    fun `refetches a stale ref`() {
        val snapshot =
            switched() +
                serverTextFrame(
                    """{"id":1,"result":{"frameTree":{"frame":{"url":"https://example.com/"}}}}"""
                ) +
                serverTextFrame("""{"id":2,"result":{}}""") +
                serverTextFrame(
                    """{"id":3,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                )
        val tap =
            switched() +
                serverTextFrame("""{"id":1,"result":{}}""") +
                serverTextFrame(
                    """{"id":2,"error":{"code":-32000,"message":"No node with given id found"}}"""
                ) +
                serverTextFrame("""{"id":3,"result":{}}""") +
                serverTextFrame(
                    """{"id":4,"result":{"nodes":[{"nodeId":"9","role":{"value":"link"},"backendDOMNodeId":9}]}}"""
                ) +
                serverTextFrame("""{"id":5,"result":{}}""") +
                serverTextFrame(quad(6)) +
                serverTextFrame(metrics(7))
        var opens = 0
        var touched = Rect(0, 0, 0, 0)
        val service =
            service(
                byteArrayOf(),
                sockets = CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _, _ ->
                    touched = rect
                    ActionResult(ok = true, backend = "inject")
                },
                open = {
                    opens++
                    ScriptedSocket(if (opens == 1) snapshot else tap)
                },
            )
        val result = runBlocking {
            service.snapshot("chrome_devtools_remote~abc")
            service.tap(
                "chrome_devtools_remote~abc",
                BrowserTapRequest(ref = "9", humanize = false),
            )
        }
        assertTrue(result.ok)
        assertEquals(Rect(435, 1474, 647, 1541), touched)
    }

    @Suppress("MissingUseCall")
    private fun service(
        response: ByteArray,
        sockets: String,
        content: suspend (String?) -> Rect = { Rect(0, 0, 1, 1) },
        touch: suspend (Rect, Boolean, InputBackend) -> ActionResult = { _, _, _ ->
            ActionResult(ok = true)
        },
        open: (String) -> DevtoolsSocket = { ScriptedSocket(response) },
    ) =
        BrowserServiceImpl(
            io = Dispatchers.IO,
            listSockets = { sockets },
            open = open,
            websocketKey = { KEY },
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

    private fun serverTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x81)
        val length = payload.size
        if (length < 126) {
            out.write(length)
        } else {
            out.write(126)
            out.write(length ushr 8)
            out.write(length and 0xFF)
        }
        out.write(payload)
        return out.toByteArray()
    }

    private fun metrics(id: Int) =
        """{"id":$id,"result":{"cssVisualViewport":{"pageX":0,"pageY":0,"clientWidth":412.19049072265625,"clientHeight":783.2380981445312},"visualViewport":{"clientWidth":1082,"clientHeight":2056}}}"""

    private fun quad(id: Int) =
        """{"id":$id,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""

    private companion object {
        const val KEY = "dGhlIHNhbXBsZSBub25jZQ=="
        const val CHROME =
            """[{"name":"chrome_devtools_remote","pid":4,"package":"com.android.chrome"}]"""
    }
}
