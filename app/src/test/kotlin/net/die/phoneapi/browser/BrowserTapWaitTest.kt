package net.die.phoneapi.browser

import kotlinx.coroutines.runBlocking
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A tap on a CSS selector waits for its element to appear, for up to waitMs. */
@Suppress("InjectDispatcher") // Scripted sockets are read on a real dispatcher.
class BrowserTapWaitTest {
    private val harness = BrowserServiceTest()

    @Test
    fun `waits for a css selector`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.serverTextFrame("""{"id":2,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":3,"result":{"nodeId":0}}""") +
                harness.serverTextFrame("""{"id":4,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":5,"result":{"nodeId":4}}""") +
                harness.serverTextFrame("""{"id":6,"result":{}}""") +
                harness.serverTextFrame(
                    """{"id":7,"result":{"quads":[[165.619049,453.68454,246.56548,453.68454,246.56548,479.27979,165.61905,479.27979]]}}"""
                ) +
                harness.serverTextFrame(harness.metrics(8))
        var touched: Rect? = null
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
                touch = { rect, _ ->
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
    fun `fails at once with no wait`() {
        val frames =
            harness.switched() +
                harness.serverTextFrame("""{"id":1,"result":{}}""") +
                harness.serverTextFrame("""{"id":2,"result":{"root":{"nodeId":1}}}""") +
                harness.serverTextFrame("""{"id":3,"result":{"nodeId":0}}""")
        val service =
            harness.service(
                frames,
                sockets = BrowserServiceTest.CHROME,
                content = { Rect(0, 283, 1080, 2339) },
            )
        val error =
            assertThrows(ApiException::class.java) {
                runBlocking {
                    service.tap(
                        "chrome_devtools_remote~abc",
                        BrowserTapRequest(selector = "a", waitMs = 0),
                    )
                }
            }
        assertEquals("not_found", error.error)
        assertThrows(ApiException::class.java) {
            runBlocking {
                service.tap(
                    "chrome_devtools_remote~abc",
                    BrowserTapRequest(selector = "a", waitMs = 60_000),
                )
            }
        }
    }
}
