package net.die.phoneapi.wait

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.die.phoneapi.browser.BrowserServiceTest
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.KeyedSocket
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.server.FakeBrowser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A `browser.dialog` wait on a page that already shows a dialog. */
// Scripted sockets are read on a real dispatcher, and the watch closes each socket it opens.
@Suppress("InjectDispatcher", "MissingUseCall")
class BrowserWatchTest {
    private val harness = BrowserServiceTest()
    private val browser =
        object : FakeBrowser() {
            override suspend fun targets() =
                listOf(
                    BrowserTarget(
                        id = TAB,
                        type = "page",
                        url = "http://a.test:8765/x",
                        socket = CHROME_SOCKET,
                    )
                )
        }
    private val dialog = WaitCondition.BrowserDialog(target = TAB)

    @Test
    fun `counts a dialog already open`() = runBlocking {
        // A page held by a dialog answers nothing, so Page.enable never returns.
        val page = KeyedSocket(byteArrayOf())
        val hosts = ArrayList<String>()
        val watch =
            BrowserWatch(browser, Dispatchers.IO, { _, _ -> page }) { host ->
                hosts += host
                true
            }
        // The watch's readers run in the caller's scope until close, which the timeout covers.
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertTrue(satisfied)
        assertEquals(listOf("a.test:8765"), hosts)
    }

    @Test
    fun `waits for chrome without one`() = runBlocking {
        val page =
            KeyedSocket(
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                    harness.serverTextFrame(
                        """{"id":2,"result":{"frameTree":{"frame":{"id":"F"}}}}"""
                    )
            )
        val watch = BrowserWatch(browser, Dispatchers.IO, { _, _ -> page }) { false }
        val satisfied =
            withTimeout(WAIT_MS) {
                watch.open(listOf(dialog), timeoutMs = 60_000)
                watch.check(dialog).satisfied.also { watch.close() }
            }
        assertFalse(satisfied)
    }

    private companion object {
        const val TAB = "${CHROME_SOCKET}~T1"
        const val WAIT_MS = 5_000L
    }
}
