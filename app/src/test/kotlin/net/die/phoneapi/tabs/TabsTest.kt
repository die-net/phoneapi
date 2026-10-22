package net.die.phoneapi.tabs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@Suppress("InjectDispatcher") // The lease's cleanup scope needs no real dispatcher here.
class TabsTest {
    private val browser = RecordingBrowser()
    private val store = MemoryStore()
    private val lease =
        ScreenLease(CoroutineScope(Dispatchers.Unconfined), cleanup = { emptyList() })
    private var front = "com.app"
    private val a = Holder.Session("a")
    private val b = Holder.Session("b")

    private fun tabs(perSession: Int = 4, total: Int = 8) =
        Tabs(browser, lease, inFront = { it == front }, store = store, perSession, total)

    @Test
    fun `opened tabs are yours`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        assertEquals(listOf(true), tabs.browserFor(a).targets().map { it.owned })
        assertEquals(emptyList<BrowserTarget>(), tabs.browserFor(b).targets())
        assertEquals("not_found", error { tabs.browserFor(b).read(tab.id, BrowserReadRequest()) })
        assertEquals(setOf(tab.id), store.ids)
    }

    @Test
    fun `claimed tabs go back`() = runBlocking {
        val tabs = tabs()
        val old = browser.add("old")
        assertEquals(listOf(false), tabs.browserFor(a).targets().map { it.owned })
        tabs.browserFor(a).read(old, BrowserReadRequest())
        assertEquals("not_found", error { tabs.browserFor(b).read(old, BrowserReadRequest()) })
        tabs.end(a)
        assertEquals(emptyList<String>(), browser.closed)
        tabs.browserFor(b).read(old, BrowserReadRequest())
        assertEquals(1, tabs.count(b))
    }

    @Test
    fun `ending closes opened tabs`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        tabs.end(a)
        assertEquals(listOf(tab.id), browser.closed)
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `caps limit opening`() = runBlocking {
        val tabs = tabs(perSession = 2, total = 3)
        repeat(2) { tabs.browserFor(a).openTab("https://example.com/") }
        assertEquals("tab_limit", error { tabs.browserFor(a).openTab("https://example.com/") })
        tabs.browserFor(b).openTab("https://example.com/")
        assertEquals("tab_limit", error { tabs.browserFor(b).openTab("https://example.com/") })
    }

    @Test
    fun `popups follow their opener`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val popup = browser.add("popup", opener = tab.id)
        assertEquals(setOf(tab.id, popup), tabs.browserFor(a).targets().map { it.id }.toSet())
        assertEquals(
            "not_found",
            error { tabs.browserFor(b).tap(popup, BrowserTapRequest(selector = "a")) },
        )
        tabs.end(a)
        assertEquals(setOf(tab.id, popup), browser.closed.toSet())
    }

    @Test
    fun `rest leaves session tabs alone`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val free = browser.add("free")
        assertEquals("not_found", error { tabs.shared.read(tab.id, BrowserReadRequest()) })
        tabs.shared.read(free, BrowserReadRequest())
        assertEquals("not_found", error { tabs.accessShared(tab.id) })
    }

    @Test
    fun `webviews are the holder's`() = runBlocking {
        val tabs = tabs()
        val view = browser.add("p", socket = "webview_devtools_remote_9", pkg = "com.app")
        assertEquals("not_found", error { tabs.browserFor(a).read(view, BrowserReadRequest()) })
        lease.withScreen(a) {}
        tabs.browserFor(a).read(view, BrowserReadRequest())
        assertEquals(listOf(view), tabs.browserFor(a).targets().map { it.id })
        front = "com.other"
        assertEquals("not_found", error { tabs.browserFor(a).read(view, BrowserReadRequest()) })
    }

    @Test
    fun `sweep closes leftovers`() = runBlocking {
        val left = browser.add("left")
        store.ids = setOf(left, "chrome_devtools_remote~gone")
        tabs().sweep()
        assertEquals(listOf(left), browser.closed)
        assertEquals(emptySet<String>(), store.ids)
    }

    @Test
    fun `waits use the only visible tab`() = runBlocking {
        val tabs = tabs()
        val tab = tabs.browserFor(a).openTab("https://example.com/")
        val waits = RecordingWaits()
        tabs.waitsFor(a, waits).wait(WaitRequest(all = listOf(WaitCondition.BrowserUrl())), SCOPES)
        assertEquals(tab.id, (waits.last?.all?.single() as? WaitCondition.BrowserUrl)?.target)
        val other = WaitRequest(all = listOf(WaitCondition.BrowserUrl(target = tab.id)))
        assertEquals("not_found", error { tabs.waitsFor(b, waits).wait(other, SCOPES) })
    }

    private suspend fun error(block: suspend () -> Unit): String =
        try {
            block()
            "no error"
        } catch (e: ApiException) {
            e.error
        }

    private class MemoryStore : TabStore {
        var ids: Set<String> = emptySet()

        override fun load(): Set<String> = ids

        override fun save(ids: Set<String>) {
            this.ids = ids
        }
    }

    private class RecordingWaits : WaitService {
        var last: WaitRequest? = null

        override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
            last = request
            return WaitResult(matched = true, timedOut = false, elapsedMs = 0)
        }
    }

    private class RecordingBrowser : BrowserService {
        val open = mutableListOf<BrowserTarget>()
        val closed = mutableListOf<String>()
        private var next = 0

        fun add(
            name: String,
            opener: String? = null,
            socket: String = "chrome_devtools_remote",
            pkg: String = "com.android.chrome",
        ): String {
            val id = "$socket~$name"
            open += BrowserTarget(id, "page", socket = socket, packageName = pkg, openerId = opener)
            return id
        }

        override suspend fun targets(): List<BrowserTarget> = open.toList()

        override suspend fun openTab(url: String): BrowserTarget {
            val id = add("t${next++}")
            return open.first { it.id == id }
        }

        override suspend fun navigate(id: String, url: String) = open.first { it.id == id }

        override suspend fun closeTab(id: String): ActionResult {
            closed += id
            open.removeAll { it.id == id }
            return ActionResult(ok = true)
        }

        override suspend fun snapshot(id: String, options: BrowserSnapshotOptions) =
            BrowserSnapshot(id = id, compact = "")

        override suspend fun read(id: String, request: BrowserReadRequest) =
            BrowserReadResult(
                extracted = "page",
                version = "0",
                totalChars = 0,
                offset = 0,
                returnedChars = 0,
                truncated = false,
            )

        override suspend fun find(id: String, request: BrowserFindRequest) =
            BrowserFindResult(extracted = "page", version = "0", totalChars = 0, total = 0)

        override suspend fun tap(id: String, request: BrowserTapRequest) = ActionResult(ok = true)

        override suspend fun swipe(id: String, request: BrowserSwipeRequest) =
            ActionResult(ok = true)

        override suspend fun gesture(id: String, request: BrowserGestureRequest) =
            ActionResult(ok = true)

        override suspend fun key(id: String, request: BrowserKeyRequest) = ActionResult(ok = true)

        override suspend fun text(id: String, request: BrowserTextRequest) = ActionResult(ok = true)

        override suspend fun evaluate(id: String, request: EvalRequest) = EvalResult()

        override suspend fun console(id: String, request: ConsoleRequest) = ConsoleResult()
    }

    private companion object {
        val SCOPES = setOf(Scope.OBSERVE, Scope.BROWSER)
    }
}
