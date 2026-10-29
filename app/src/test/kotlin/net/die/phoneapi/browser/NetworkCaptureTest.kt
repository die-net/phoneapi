package net.die.phoneapi.browser

import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Recording a tab's JSON responses for scope data. */
// Scripted sockets are read on a real dispatcher, and each capture closes its own socket.
@Suppress("InjectDispatcher", "MissingUseCall")
class NetworkCaptureTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `logs finished JSON responses`() {
        val log = CaptureLog(TAB_FRAME)
        log.apply(received("1", "https://a.test/api", "application/json"))
        log.apply(received("2", "https://a.test/x.png", "image/png"))
        log.apply(received("3", "https://a.test/feed", "application/feed+json; charset=utf-8"))
        log.apply(received("4", "https://a.test/broken", "application/json"))
        log.apply(event("Network.dataReceived", """{"requestId":"1","dataLength":10}"""))
        log.apply(event("Network.dataReceived", """{"requestId":"1","dataLength":5}"""))
        log.apply(event("Network.dataReceived", """{"requestId":"2","dataLength":99}"""))
        log.apply(event("Network.loadingFailed", """{"requestId":"4"}"""))
        log.apply(finished("2"))
        log.apply(finished("3"))
        log.apply(finished("1"))
        log.apply(finished("4"))
        val responses = log.responses
        assertEquals(listOf("response-1", "response-2"), responses.map { it.name })
        assertEquals(listOf("3", "1"), responses.map { it.requestId })
        assertEquals(15, responses[1].bytes)
    }

    @Test
    fun `new pages drop old responses`() {
        val log = CaptureLog(TAB_FRAME)
        log.apply(received("1", "https://a.test/api", "application/json", loader = "L1"))
        log.apply(finished("1"))
        log.apply(received("2", "https://a.test/slow", "application/json", loader = "L1"))
        // A subframe's document is not a new page, and a JSON document is not a response.
        log.apply(received("F", "https://b.test/", "text/html", type = "Document", frame = "sub"))
        log.apply(
            received("D", "https://a.test/d", "application/json", type = "Document", frame = "sub")
        )
        log.apply(finished("D"))
        assertEquals(1, log.responses.size)
        log.apply(received("N", "https://a.test/next", "text/html", "L2", "Document"))
        log.apply(finished("2"))
        log.apply(received("3", "https://a.test/api", "application/json", loader = "L2"))
        log.apply(finished("3"))
        assertEquals(listOf("3"), log.responses.map { it.requestId })
    }

    @Test
    fun `keeps the latest responses`() {
        val log = CaptureLog(TAB_FRAME)
        repeat(CaptureLog.MAX_RESPONSES + 3) {
            log.apply(received("$it", "https://a.test/$it", "application/json"))
            log.apply(finished("$it"))
        }
        val responses = log.responses
        assertEquals(CaptureLog.MAX_RESPONSES, responses.size)
        assertEquals("response-4", responses.first().name)
    }

    @Test
    fun `checks bodies are JSON`() {
        assertEquals("""{"a":[1,2]}""", jsonText("""{"a":[1,2]}"""))
        assertEquals("""[1]""", jsonText(")]}'\n[1]")?.trim())
        assertEquals("""{"a":1}""", jsonText("""for (;;);{"a":1}"""))
        assertEquals("\"x\"", jsonText(" )]}',\"x\""))
        assertNull(jsonText(""))
        assertNull(jsonText("  "))
        assertNull(jsonText("""{"a" 1}"""))
        assertNull(jsonText("""{"a":1} trailing"""))
        assertNull(jsonText("callback({\"a\":1})"))
    }

    @Test
    fun `reads recorded bodies`() = runBlocking {
        val encoded = Base64.getEncoder().encodeToString("""{"b":2}""".toByteArray())
        val page =
            StallingSocket(
                harness.switched() +
                    reply(1, "{}") +
                    frame(received("r1", "https://a.test/one", "application/json")) +
                    frame(finished("r1")) +
                    frame(received("r2", "https://a.test/two", "application/json", status = 404)) +
                    frame(finished("r2")) +
                    frame(received("r3", "https://a.test/bad", "application/json")) +
                    frame(finished("r3")) +
                    frame(received("r4", "https://a.test/gone", "application/json")) +
                    frame(finished("r4")) +
                    reply(2, """{"body":"{\"a\":1}","base64Encoded":false}""") +
                    reply(3, """{"body":"$encoded","base64Encoded":true}""") +
                    reply(4, """{"body":"<html>","base64Encoded":false}""") +
                    harness.serverTextFrame(
                        """{"id":5,"error":{"message":"No resource with given identifier found"}}"""
                    )
            )
        val capture = capture(page)
        assertEquals(0, capture.start(TAB))
        withTimeout(WAIT_MS) { while (capture.listed(TAB).size < 4) delay(10) }
        assertEquals(4, capture.start(TAB))
        val listed = capture.listed(TAB)
        assertEquals("https://a.test/two (http 404)", listed[1].source)
        val blobs = capture.blobs(TAB, null, DATA_BUDGET)
        assertEquals("""{"a":1}""", blobs[0].text)
        assertEquals("""{"b":2}""", blobs[1].text)
        assertEquals("response-3 is not JSON", blobs[2].missing)
        assertTrue(blobs[3].missing.orEmpty().startsWith("Chrome no longer has the body"))
        // Scripted replies can arrive before a command is written, so wait for the writes.
        withTimeout(WAIT_MS) {
            while (page.methods().count { it == "Network.getResponseBody" } < 4) delay(10)
        }
        assertEquals("Network.enable", page.methods().first())
        capture.stop(TAB)
        assertEquals(emptyList<Any>(), capture.listed(TAB))
    }

    @Test
    fun `reads wanted bodies in budget`() = runBlocking {
        val page =
            StallingSocket(
                harness.switched() +
                    reply(1, "{}") +
                    frame(received("r1", "https://a.test/one", "application/json")) +
                    frame(event("Network.dataReceived", """{"requestId":"r1","dataLength":7}""")) +
                    frame(finished("r1")) +
                    frame(received("r2", "https://a.test/two", "application/json")) +
                    frame(event("Network.dataReceived", """{"requestId":"r2","dataLength":7}""")) +
                    frame(finished("r2")) +
                    reply(2, """{"body":"{\"b\":2}","base64Encoded":false}""")
            )
        val capture = capture(page)
        capture.start(TAB)
        withTimeout(WAIT_MS) { while (capture.listed(TAB).size < 2) delay(10) }
        val wanted = capture.blobs(TAB, listOf("response-2"), DATA_BUDGET)
        assertNull(wanted[0].text)
        assertEquals("""{"b":2}""", wanted[1].text)
        // Nothing answers a second body request, so fetching one here would fail the test.
        val tight = capture.blobs(TAB, null, 6)
        assertTrue(tight.all { it.omitted })
        capture.stop(TAB)
    }

    @Test
    fun `ends when the tab closes`() = runBlocking {
        // The page answers, then its socket ends, as when the tab closes.
        val pages =
            sockets(BrowserServiceTest.ScriptedSocket(harness.switched() + reply(1, "{}")), fresh())
        val capture = NetworkCapture(CoroutineScope(Dispatchers.IO), pages::next)
        capture.start(TAB)
        // A start while one records opens nothing; once it has ended, a start opens a new one.
        withTimeout(WAIT_MS) {
            while (pages.opened < 2) {
                capture.start(TAB)
                delay(10)
            }
        }
        capture.stop(TAB)
    }

    @Test
    fun `ends when unused`() = runBlocking {
        val pages = sockets(fresh(), fresh())
        val capture = NetworkCapture(CoroutineScope(Dispatchers.IO), pages::next, idleMs = 100)
        capture.start(TAB)
        capture.start(TAB)
        assertEquals(1, pages.opened)
        delay(400)
        capture.start(TAB)
        assertEquals(2, pages.opened)
        capture.stop(TAB)
    }

    @Test
    fun `a refused start leaves nothing`() = runBlocking {
        val refused =
            StallingSocket(
                harness.switched() +
                    harness.serverTextFrame("""{"id":1,"error":{"message":"Not allowed"}}""")
            )
        val pages = sockets(refused, fresh())
        val capture = NetworkCapture(CoroutineScope(Dispatchers.IO), pages::next)
        assertThrows(ApiException::class.java) { runBlocking { capture.start(TAB) } }
        assertEquals(0, capture.start(TAB))
        assertEquals(2, pages.opened)
        capture.stop(TAB)
    }

    /** Hands out [list] in order, one per capture opened, counting them. */
    private inner class Sockets(private val list: List<DevtoolsSocket>) {
        var opened = 0
            private set

        fun next(id: String): PageLink {
            check(id == TAB)
            return link(list[opened++])
        }
    }

    private fun sockets(vararg list: DevtoolsSocket) = Sockets(list.toList())

    /** A page that answers Network.enable, then sends nothing more. */
    private fun fresh() = StallingSocket(harness.switched() + reply(1, "{}"))

    private fun capture(page: DevtoolsSocket) =
        NetworkCapture(CoroutineScope(Dispatchers.IO), { link(page) })

    private fun link(page: DevtoolsSocket): PageLink {
        val cdp = CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
        cdp.open("/devtools/page/t")
        return PageLink(cdp, page)
    }

    private fun reply(id: Int, result: String) =
        harness.serverTextFrame("""{"id":$id,"result":$result}""")

    private fun frame(event: CdpEvent) =
        harness.serverTextFrame("""{"method":"${event.method}","params":${event.params}}""")

    private fun event(method: String, params: String) =
        CdpEvent(method, Json.parseToJsonElement(params).jsonObject)

    private fun received(
        id: String,
        url: String,
        mime: String,
        loader: String = "L",
        type: String = "Fetch",
        frame: String = TAB_FRAME,
        status: Int = 200,
    ) =
        event(
            "Network.responseReceived",
            """{"requestId":"$id","loaderId":"$loader","type":"$type","frameId":"$frame",""" +
                """"response":{"url":"$url","status":$status,"mimeType":"$mime"}}""",
        )

    private fun finished(id: String) =
        event("Network.loadingFinished", """{"requestId":"$id","encodedDataLength":1}""")

    private companion object {
        const val TAB_FRAME = "T1"
        const val TAB = "${CHROME_SOCKET}~$TAB_FRAME"
        const val WAIT_MS = 5_000L
    }
}
