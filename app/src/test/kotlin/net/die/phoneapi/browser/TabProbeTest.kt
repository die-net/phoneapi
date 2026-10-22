package net.die.phoneapi.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.die.phoneapi.model.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How the probe tells an answering tab from an unloaded one and a frozen Chrome. */
// Scripted sockets are read on a real dispatcher, and onPage closes each page socket.
@Suppress("InjectDispatcher", "MissingUseCall")
class TabProbeTest {
    private val harness = BrowserServiceTest()
    private var browserReplies = true

    private fun probe() =
        TabProbe(
            Dispatchers.IO,
            open = {
                val reply = harness.serverTextFrame("""{"id":1,"result":{}}""")
                StallingSocket(harness.switched() + if (browserReplies) reply else byteArrayOf())
            },
            websocketKey = { BrowserServiceTest.KEY },
            probeMs = 200,
            reloadMs = 2_000,
        )

    @Test
    fun `live tab is left alone`() {
        val page = StallingSocket(harness.switched() + empty(1))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
        assertEquals(listOf("Inspector.enable"), page.methods())
    }

    @Test
    fun `unloaded tab reloads on read`() {
        // Chrome reports the crash before answering, then the reload and the fresh readyState.
        // Replies are queued ahead, so later commands may not be written yet.
        val page = StallingSocket(harness.switched() + crashed() + value(3, "\"complete\""))
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertTrue(reloaded)
    }

    @Test
    fun `unloaded tab refuses input`() {
        val page = StallingSocket(harness.switched() + crashed())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = false) } }
        assertEquals("tab_reloaded", error)
    }

    @Test
    fun `silent page with live browser`() {
        // The page does not answer, but Chrome does: not a crash, so the call goes ahead.
        val page = StallingSocket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, CHROME_SOCKET, reading = true) }
        assertFalse(reloaded)
    }

    @Test
    fun `silent browser is frozen`() {
        browserReplies = false
        val page = StallingSocket(harness.switched())
        val error = onPage(page) { failure { probe().revive(it, CHROME_SOCKET, reading = true) } }
        assertEquals("browser_frozen", error)
        assertFalse("Page.reload" in page.methods())
    }

    @Test
    fun `running tab takes input`() {
        val page = StallingSocket(harness.switched() + value(1, "0") + value(2, "true"))
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `frozen tab refuses input`() {
        // DevTools script runs, but the page's own message never arrives.
        val page = StallingSocket(harness.switched() + value(1, "0"))
        assertEquals(
            "tab_frozen",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `busy tab takes input`() {
        // A busy main thread answers no script at all, which is not a pause.
        val page = StallingSocket(harness.switched())
        assertEquals(
            "no error",
            onPage(page) { failure { probe().requireRunning(it, CHROME_SOCKET) } },
        )
    }

    @Test
    fun `webviews are not probed`() {
        val page = StallingSocket(harness.switched())
        val reloaded = onPage(page) { probe().revive(it, "webview_devtools_remote_1", true) }
        assertFalse(reloaded)
        assertEquals(emptyList<String>(), page.methods())
    }

    private fun <T> onPage(page: StallingSocket, block: suspend (CdpSession) -> T): T =
        runBlocking {
            try {
                CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
                    .use { cdp ->
                        cdp.open("/devtools/page/t")
                        block(cdp)
                    }
            } finally {
                page.close()
            }
        }

    private suspend fun failure(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private fun empty(id: Int) = harness.serverTextFrame("""{"id":$id,"result":{}}""")

    /** `Inspector.targetCrashed`, then the replies to `Inspector.enable` and `Page.reload`. */
    private fun crashed() =
        harness.serverTextFrame("""{"method":"Inspector.targetCrashed","params":{}}""") +
            empty(1) +
            empty(2)

    private fun value(id: Int, json: String) =
        harness.serverTextFrame("""{"id":$id,"result":{"result":{"value":$json}}}""")

    /** Plays [replies], then reads nothing until closed, as a page that stopped answering. */
    private class StallingSocket(replies: ByteArray) : DevtoolsSocket {
        private val closed = CountDownLatch(1)
        private val played = ByteArrayInputStream(replies)
        override val output: OutputStream = ByteArrayOutputStream()
        override val input: InputStream =
            object : InputStream() {
                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = played.read(b, off, len)
                    if (n > 0) return n
                    if (!closed.await(STALL_S, TimeUnit.SECONDS)) throw IOException("not closed")
                    return -1
                }
            }

        override fun close() = closed.countDown()

        /** The CDP methods sent, in order. */
        fun methods(): List<String> {
            val text = String((output as ByteArrayOutputStream).toByteArray(), Charsets.ISO_8859_1)
            return METHOD.findAll(unmasked(text)).map { it.groupValues[1] }.toList()
        }

        private fun unmasked(text: String): String {
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            var at = text.indexOf("\r\n\r\n") + HEADER_END
            val out = StringBuilder()
            while (at + 2 <= bytes.size) {
                var length = bytes[at + 1].toInt() and LENGTH_BITS
                var header = 2
                if (length == LENGTH_16) {
                    length =
                        (bytes[at + 2].toUByte().toInt() shl 8) or bytes[at + 3].toUByte().toInt()
                    header = 4
                }
                val mask = bytes.copyOfRange(at + header, at + header + MASK_BYTES)
                val start = at + header + MASK_BYTES
                for (i in 0 until length) {
                    out.append((bytes[start + i].toInt() xor mask[i % MASK_BYTES].toInt()).toChar())
                }
                out.append('\n')
                at = start + length
            }
            return out.toString()
        }
    }

    private companion object {
        const val STALL_S = 10L
        const val BYTE = 0xFF
        const val HEADER_END = 4
        const val LENGTH_BITS = 0x7F
        const val LENGTH_16 = 126
        const val MASK_BYTES = 4
        val METHOD = Regex(""""method":"([^"]+)"""")
    }
}
