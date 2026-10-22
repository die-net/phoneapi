package net.die.phoneapi.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The background check of whether a tab's timers run. */
// Scripted sockets are read on a real dispatcher, and each check closes its own socket.
@Suppress("InjectDispatcher", "MissingUseCall")
class TimerWatchTest {
    private val harness = BrowserServiceTest()
    private var timerFires = true

    private fun watch() =
        TimerWatch(
            CoroutineScope(Dispatchers.IO),
            onPage = { _, block ->
                val replies =
                    harness.switched() + world() + if (timerFires) fired() else byteArrayOf()
                val page = StallingSocket(replies)
                try {
                    CdpSession(page.input, page.output, Dispatchers.IO) { BrowserServiceTest.KEY }
                        .use { cdp ->
                            cdp.open("/devtools/page/t")
                            block(cdp)
                        }
                } finally {
                    page.close()
                }
            },
            stallMs = STALL_MS,
            watchMs = WATCH_MS,
            setupMs = SETUP_MS,
        )

    @Test
    fun `running timers are fine`() = runBlocking {
        val watch = watch()
        watch.arm("t")
        delay(STALL_MS * 3)
        assertFalse(watch.stalled("t"))
    }

    @Test
    fun `stopped timers show`() = runBlocking {
        timerFires = false
        val watch = watch()
        watch.arm("t")
        // Overdue while the check still waits, and stalled once it gives up.
        withTimeout(WATCH_MS * 2) { while (!watch.stalled("t")) delay(10) }
        delay(WATCH_MS)
        assertTrue(watch.stalled("t"))
        assertTrue(watch.confirm("t"))
    }

    @Test
    fun `waits keep a found stop`() = runBlocking {
        timerFires = false
        val watch = watch()
        val tab = "$CHROME_SOCKET~t"
        watch.watch(tab)
        withTimeout(WATCH_MS * 2) { while (!watch.stalled(tab)) delay(10) }
        // A wait that starts its own check must not reset one that found the timers stopped.
        watch.watch(tab)
        assertTrue(watch.stalled(tab))
    }

    @Test
    fun `revived tabs clear`() = runBlocking {
        timerFires = false
        val watch = watch()
        watch.arm("t")
        withTimeout(WATCH_MS * 2) { while (!watch.stalled("t")) delay(10) }
        timerFires = true
        assertFalse(watch.confirm("t"))
        assertFalse(watch.stalled("t"))
    }

    @Test
    fun `busy pages are unknown`() = runBlocking {
        // No answer even to setting up the isolated world, as while script runs: not a stall.
        val watch =
            TimerWatch(
                CoroutineScope(Dispatchers.IO),
                onPage = { _, block ->
                    val page = StallingSocket(harness.switched())
                    try {
                        CdpSession(page.input, page.output, Dispatchers.IO) {
                                BrowserServiceTest.KEY
                            }
                            .use { cdp ->
                                cdp.open("/devtools/page/t")
                                block(cdp)
                            }
                    } finally {
                        page.close()
                    }
                },
                stallMs = STALL_MS,
                watchMs = WATCH_MS,
                setupMs = SETUP_MS,
            )
        watch.arm("t")
        delay(SETUP_MS * 3)
        assertFalse(watch.stalled("t"))
    }

    private fun world() =
        harness.serverTextFrame("""{"id":1,"result":{"frameTree":{"frame":{"id":"F"}}}}""") +
            harness.serverTextFrame("""{"id":2,"result":{"executionContextId":7}}""")

    private fun fired() =
        harness.serverTextFrame("""{"id":3,"result":{"result":{"type":"boolean","value":true}}}""")

    private companion object {
        const val STALL_MS = 100L
        const val WATCH_MS = 300L
        const val SETUP_MS = 150L
    }
}
