package net.die.phoneapi.wait

import android.os.SystemClock
import java.io.IOException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.die.phoneapi.AppGraph
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.CdpSession
import net.die.phoneapi.browser.HelperDevtoolsSocket
import net.die.phoneapi.browser.WebSocketClient
import net.die.phoneapi.browser.parseBrowserTargetId
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.WaitCondition

/**
 * CDP sessions held for one `POST /v1/wait`. Events update [PageModel] and wake the wait loop.
 * Closing the sockets unblocks the reader threads.
 */
internal class BrowserWatch(private val graph: AppGraph) {
    private val signals =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val changes: Flow<Unit> = signals
    private val pages = HashMap<String, PageSession>()
    private val targets = TargetModel()
    private val sockets = ArrayList<HelperDevtoolsSocket>()
    private var defaultId: String? = null

    suspend fun open(conditions: List<WaitCondition>, timeoutMs: Long) {
        if (conditions.none(::isBrowserCondition)) return
        try {
            connect(conditions, timeoutMs)
        } catch (e: ApiException) {
            close()
            throw e
        } catch (e: IOException) {
            close()
            throw ApiException(
                502,
                "cdp_error",
                e.message ?: "DevTools connection failed",
                cause = e,
            )
        }
    }

    suspend fun check(condition: WaitCondition): Check =
        when (condition) {
            is WaitCondition.BrowserUrl ->
                Check(
                    session(condition)
                        .model
                        .urlMatches(
                            condition.equals,
                            condition.contains,
                            condition.regex?.let(::compileRegex),
                        )
                )
            is WaitCondition.BrowserLifecycle ->
                Check(session(condition).model.sawLifecycle(condition.name))
            is WaitCondition.BrowserNetworkIdle ->
                session(condition)
                    .model
                    .networkIdle(
                        condition.maxInflight,
                        condition.quietMs,
                        SystemClock.uptimeMillis(),
                    )
            is WaitCondition.BrowserElement -> element(session(condition), condition)
            is WaitCondition.BrowserRequest ->
                Check(
                    session(condition)
                        .model
                        .requestMatched(
                            condition.urlContains,
                            condition.urlRegex?.let(::compileRegex),
                            condition.method,
                            condition.status,
                        )
                )
            is WaitCondition.BrowserDialog -> Check(session(condition).model.dialogOpen())
            is WaitCondition.BrowserNewTarget -> Check(targets.matches(condition.urlContains))
            is WaitCondition.BrowserLog ->
                Check(session(condition).model.logMatched(condition.level, condition.textContains))
            is WaitCondition.BrowserSettled ->
                session(condition).model.settled(condition.quietMs, SystemClock.uptimeMillis())
            is WaitCondition.Node,
            is WaitCondition.Window,
            is WaitCondition.Ime,
            is WaitCondition.Idle,
            is WaitCondition.Screen,
            is WaitCondition.Keyguard -> Check(satisfied = false)
        }

    fun close() {
        val open = sockets.toList()
        sockets.clear()
        open.forEach { it.close() }
    }

    private suspend fun connect(conditions: List<WaitCondition>, timeoutMs: Long) {
        val explicit = LinkedHashSet<String>()
        var needsDefault = false
        var needsBrowser = false
        conditions.filter(::isBrowserCondition).forEach { condition ->
            if (condition is WaitCondition.BrowserNewTarget) {
                needsBrowser = true
            } else {
                val target = browserTarget(condition)
                if (target == null) needsDefault = true else explicit += target
            }
        }
        if (needsDefault) defaultId = resolveDefault()
        (explicit + listOfNotNull(defaultId)).forEach { id -> openPage(id, conditions, timeoutMs) }
        if (needsBrowser) openBrowser(timeoutMs)
    }

    private suspend fun resolveDefault(): String {
        val pages = graph.browser.targets().filter { it.type == "page" }
        if (pages.isEmpty()) {
            throw ApiException.unavailable("browser_unavailable", "Chrome has no open page")
        }
        if (pages.size > 1) {
            throw ApiException.badRequest("Pass target when more than one page is open")
        }
        return pages.first().id
    }

    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun openPage(id: String, conditions: List<WaitCondition>, timeoutMs: Long) {
        val (socketName, chromeId) = parseBrowserTargetId(id)
        val devtools = openSocket(socketName, timeoutMs)
        val ws = WebSocketClient(devtools.input, devtools.output)
        handshake(ws, "/devtools/page/$chromeId")
        val session = CdpSession(ws)
        val model = PageModel(SystemClock.uptimeMillis())
        val page = PageSession(model, session)
        session.onEvent { method, params ->
            model.apply(method, params, SystemClock.uptimeMillis())
            if (method == "DOM.documentUpdated") page.forgetDocument()
            signals.tryEmit(Unit)
        }
        session.start()
        enable(session, domainsFor(conditions.filter { onPage(it, id) }))
        seed(session, model)
        pages[id] = page
    }

    @Suppress("MissingUseCall") // The watch closes every socket it opened.
    private suspend fun openBrowser(timeoutMs: Long) {
        val devtools = openSocket(CHROME_SOCKET, timeoutMs)
        val ws = WebSocketClient(devtools.input, devtools.output)
        handshake(ws, "/devtools/browser")
        val session = CdpSession(ws)
        session.onEvent { method, params ->
            targets.apply(method, params)
            signals.tryEmit(Unit)
        }
        session.start()
        session.call("Target.setDiscoverTargets", buildJsonObject { put("discover", true) })
    }

    @Suppress("MissingUseCall") // Closed with the rest of the watch.
    private fun openSocket(name: String, timeoutMs: Long): HelperDevtoolsSocket {
        val socket =
            HelperDevtoolsSocket(graph.helper, name, readTimeoutMs = timeoutMs + SOCKET_SLACK_MS)
        sockets += socket
        return socket
    }

    private fun session(condition: WaitCondition): PageSession {
        val id =
            browserTarget(condition)
                ?: defaultId
                ?: throw ApiException.badRequest("Pass target when more than one page is open")
        return pages[id] ?: throw ApiException.badRequest("Unknown browser target")
    }

    private fun onPage(condition: WaitCondition, id: String): Boolean {
        if (!isBrowserCondition(condition) || condition is WaitCondition.BrowserNewTarget)
            return false
        return (browserTarget(condition) ?: defaultId) == id
    }

    private suspend fun element(page: PageSession, condition: WaitCondition.BrowserElement): Check {
        val nodeId = findNode(page, condition)
        return when (condition.state) {
            "absent" -> Check(nodeId == 0)
            "present" -> Check(nodeId != 0)
            else -> Check(nodeId != 0 && visible(page, nodeId))
        }
    }

    private suspend fun findNode(page: PageSession, condition: WaitCondition.BrowserElement): Int {
        val root = page.rootId ?: documentRoot(page)
        return if (condition.by == "css") queryCss(page, root, condition.selector)
        else querySearch(page, condition.selector)
    }

    private suspend fun documentRoot(page: PageSession): Int {
        val doc = page.cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
        val id =
            ((doc["root"] as? JsonObject)?.get("nodeId") as? JsonPrimitive)?.intOrNull
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a document")
        page.rootId = id
        return id
    }

    private suspend fun queryCss(page: PageSession, root: Int, selector: String): Int {
        val result =
            page.cdp.call(
                "DOM.querySelector",
                buildJsonObject {
                    put("nodeId", root)
                    put("selector", selector)
                },
            )
        return (result["nodeId"] as? JsonPrimitive)?.intOrNull ?: 0
    }

    private suspend fun querySearch(page: PageSession, query: String): Int {
        val started = page.cdp.call("DOM.performSearch", buildJsonObject { put("query", query) })
        val count = (started["resultCount"] as? JsonPrimitive)?.intOrNull ?: 0
        val searchId = (started["searchId"] as? JsonPrimitive)?.contentOrNull
        if (count == 0 || searchId == null) return 0
        val results =
            page.cdp.call(
                "DOM.getSearchResults",
                buildJsonObject {
                    put("searchId", searchId)
                    put("fromIndex", 0)
                    put("toIndex", 1)
                },
            )
        page.cdp.call("DOM.discardSearchResults", buildJsonObject { put("searchId", searchId) })
        val ids = results["nodeIds"] as? JsonArray
        return (ids?.firstOrNull() as? JsonPrimitive)?.intOrNull ?: 0
    }

    private suspend fun visible(page: PageSession, nodeId: Int): Boolean =
        try {
            val quads =
                page.cdp.call("DOM.getContentQuads", buildJsonObject { put("nodeId", nodeId) })
            val list = quads["quads"] as? JsonArray
            list != null && list.isNotEmpty()
        } catch (e: ApiException) {
            if (e.error != "cdp_error") throw e
            false
        }

    private suspend fun enable(session: CdpSession, domains: Set<String>) {
        if ("Page" in domains) session.call("Page.enable")
        if ("Network" in domains) session.call("Network.enable")
        if ("LayerTree" in domains) session.call("LayerTree.enable")
        if ("Log" in domains) session.call("Log.enable")
        if ("DOM" in domains) session.call("DOM.enable")
    }

    private suspend fun seed(session: CdpSession, model: PageModel) {
        val tree = session.call("Page.getFrameTree")
        val frame = (tree["frameTree"] as? JsonObject)?.get("frame") as? JsonObject ?: return
        model.seed(
            (frame["id"] as? JsonPrimitive)?.contentOrNull,
            (frame["url"] as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun handshake(socket: WebSocketClient, path: String) {
        try {
            socket.handshake(path)
        } catch (e: IOException) {
            throw ApiException(
                502,
                "cdp_error",
                e.message ?: "DevTools connection failed",
                cause = e,
            )
        }
    }

    private class PageSession(val model: PageModel, val cdp: CdpSession) {
        @Volatile var rootId: Int? = null

        fun forgetDocument() {
            rootId = null
        }
    }

    private companion object {
        const val SOCKET_SLACK_MS = 20_000L
    }
}
