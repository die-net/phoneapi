package net.die.phoneapi.browser

import android.os.RemoteException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.ConsoleEntry
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.Rect

/**
 * Talks to Chrome and WebView over the helper's DevTools pipe. One service call keeps a single
 * connection. The last accessibility tree for a target is kept so a later tap can reuse a snapshot
 * ref without fetching the tree again.
 */
internal class BrowserServiceImpl(
    private val io: CoroutineContext,
    private val listSockets: suspend () -> String,
    private val open: (String) -> DevtoolsSocket,
    private val websocketKey: () -> String = ::websocketKey,
    private val contentBounds: suspend (String?) -> Rect = { Rect(0, 0, 0, 0) },
    private val touchAt: suspend (Rect, Boolean, InputBackend) -> ActionResult = { _, _, _ ->
        ActionResult(ok = false)
    },
) : BrowserService {
    private val axTrees = ConcurrentHashMap<String, JsonObject>()

    override suspend fun targets(): List<BrowserTarget> {
        val sockets = socketList()
        val out = ArrayList<BrowserTarget>()
        val failures = ArrayList<String>()
        var status = 502
        for (socket in sockets) {
            try {
                out += listOne(socket)
            } catch (e: ApiException) {
                if (e.error == "helper_unavailable") throw e
                status = e.status
                failures += e.message ?: e.error
            }
        }
        if (out.isEmpty() && failures.isNotEmpty()) {
            throw ApiException(status, "cdp_error", failures.joinToString("; "))
        }
        return out
    }

    override suspend fun openTab(url: String): BrowserTarget {
        val pageUrl = webUrl(url)
        val chrome =
            socketList().firstOrNull { it.name == CHROME_SOCKET }
                ?: throw ApiException.unavailable(
                    "browser_unavailable",
                    "Chrome's DevTools socket is not open. Chrome has to be running.",
                )
        val created =
            useCdp(chrome.name, "/devtools/browser") { cdp ->
                cdp.call("Target.createTarget", buildJsonObject { put("url", pageUrl) })
            }
        val chromeId =
            created.decodeCdp<CreatedTarget>()?.targetId
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a target id")
        return BrowserTarget(
            id = browserTargetId(chrome.name, chromeId),
            type = "page",
            url = pageUrl,
            socket = chrome.name,
            pid = chrome.pid,
            packageName = chrome.packageName,
        )
    }

    override suspend fun navigate(id: String, url: String): BrowserTarget {
        val pageUrl = webUrl(url)
        val (socket, chromeId) = parseBrowserTargetId(id)
        axTrees.remove(id)
        usePage(socket, chromeId) { cdp ->
            val error =
                cdp.call("Page.navigate", buildJsonObject { put("url", pageUrl) })
                    .decodeCdp<NavigateResult>()
                    ?.errorText
            if (!error.isNullOrBlank()) throw ApiException.badRequest(error)
        }
        return BrowserTarget(id = id, type = "page", url = pageUrl, socket = socket)
    }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult {
        val wanted = tapTarget(request)
        val (socket, chromeId) = parseBrowserTargetId(id)
        val pkg = socketList().firstOrNull { it.name == socket }?.packageName
        return usePage(socket, chromeId) { cdp ->
            cdp.call("Page.bringToFront")
            delay(FRONT_SETTLE_MS)
            val quads = elementQuads(cdp, id, wanted)
            val target = screenTarget(quads, cdp.call("Page.getLayoutMetrics"), contentBounds(pkg))
            touchAt(target, request.humanize, request.backend)
        }
    }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
        val expression = request.expression
        if (expression.isBlank() || expression.length > MAX_EXPRESSION) {
            throw ApiException.badRequest("expression must be 1..$MAX_EXPRESSION characters")
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp ->
            evaluateIn(cdp, expression, request.awaitPromise)
        }
    }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult {
        if (request.timeoutMs !in 0..MAX_CONSOLE_MS) {
            throw ApiException.badRequest("timeoutMs must be 0..$MAX_CONSOLE_MS")
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        return useCdp(socket, "/devtools/page/$chromeId") { cdp ->
            collectLogs(cdp, request.timeoutMs)
        }
    }

    override suspend fun snapshot(id: String): BrowserSnapshot {
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp ->
            val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
            val url = loaded?.frameTree?.frame?.url
            cdp.call("Accessibility.enable")
            val tree = cdp.call("Accessibility.getFullAXTree")
            axTrees[id] = tree
            val text = formatAxTree(tree, url)
            BrowserSnapshot(id = id, url = url, title = text.title, compact = text.compact)
        }
    }

    private suspend fun listOne(socket: HelperSocket): List<BrowserTarget> =
        useSocket(socket.name) { input, output ->
            parseTargets(socket, httpGet(input, output, "/json/list", MAX_BODY))
        }

    private suspend fun <T> usePage(
        socket: String,
        chromeId: String,
        block: suspend (CdpSession) -> T,
    ): T {
        var last: ApiException? = null
        repeat(PAGE_ATTEMPTS) { attempt ->
            try {
                return useCdp(socket, "/devtools/page/$chromeId", block)
            } catch (e: ApiException) {
                if (e.status < HTTP_SERVER_ERROR || e.error == "helper_unavailable") throw e
                last = e
                if (attempt < PAGE_ATTEMPTS - 1) delay(PAGE_RETRY_MS)
            }
        }
        throw last ?: ApiException(502, "cdp_error", "DevTools did not answer")
    }

    private suspend fun <T> useCdp(
        socket: String,
        path: String,
        block: suspend (CdpSession) -> T,
    ): T =
        useSocket(socket) { input, output ->
            CdpSession(input, output, io, websocketKey).use { cdp ->
                cdp.open(path)
                block(cdp)
            }
        }

    private suspend fun elementQuads(
        cdp: CdpSession,
        targetId: String,
        target: TapTarget,
    ): JsonArray =
        when (target) {
            is TapTarget.Ref -> refQuads(cdp, targetId, target.id)
            is TapTarget.Css -> boxed(cdp, cssNode(cdp, target.selector))
        }

    private suspend fun refQuads(cdp: CdpSession, targetId: String, ref: String): JsonArray {
        val backend = cachedBackend(targetId, ref)
        if (backend != null) {
            try {
                return boxed(cdp, backendParams(backend))
            } catch (e: ApiException) {
                if (e.error != "cdp_error") throw e
                axTrees.remove(targetId)
            }
        }
        return boxed(cdp, backendParams(fetchBackend(cdp, targetId, ref)))
    }

    private suspend fun boxed(cdp: CdpSession, params: JsonObject): JsonArray {
        cdp.call("DOM.scrollIntoViewIfNeeded", params)
        val quads = quadsOf(cdp.call("DOM.getContentQuads", params))
        if (quads.isEmpty()) throw ApiException(409, "offscreen", "The node has no box on the page")
        return quads
    }

    private fun cachedBackend(targetId: String, ref: String): Int? {
        val tree = axTrees[targetId] ?: return null
        return try {
            elementBackendId(tree, ref)
        } catch (_: ApiException) {
            null
        }
    }

    private suspend fun fetchBackend(cdp: CdpSession, targetId: String, ref: String): Int {
        cdp.call("Accessibility.enable")
        val tree = cdp.call("Accessibility.getFullAXTree")
        axTrees[targetId] = tree
        return elementBackendId(tree, ref)
    }

    private fun backendParams(backend: Int): JsonObject = buildJsonObject {
        put("backendNodeId", backend)
    }

    private fun quadsOf(result: JsonObject): JsonArray {
        val quads = result.decodeCdp<QuadResult>()?.quads ?: return JsonArray(emptyList())
        return JsonArray(quads)
    }

    private suspend fun cssNode(cdp: CdpSession, selector: String): JsonObject {
        val root =
            cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
                .decodeCdp<DocumentResult>()
                ?.root
                ?.nodeId ?: 0
        if (root == 0) throw ApiException(502, "cdp_error", "Chrome did not return a document")
        val nodeId =
            cdp.call(
                    "DOM.querySelector",
                    buildJsonObject {
                        put("nodeId", root)
                        put("selector", selector)
                    },
                )
                .decodeCdp<QueryResult>()
                ?.nodeId ?: 0
        if (nodeId == 0) throw ApiException.notFound("No element matches the selector")
        return buildJsonObject { put("nodeId", nodeId) }
    }

    private fun tapTarget(request: BrowserTapRequest): TapTarget {
        val selector = request.selector?.trim()?.ifEmpty { null }
        val ref = request.ref?.trim()?.ifEmpty { null }
        if (selector != null && ref != null) {
            throw ApiException.badRequest("Pass a ref or a CSS selector, not both")
        }
        if (selector != null) return TapTarget.Css(cssSelector(selector))
        if (ref != null) return TapTarget.Ref(nodeRef(ref))
        throw ApiException.badRequest("Pass a ref or a CSS selector")
    }

    private fun cssSelector(selector: String): String {
        if (
            selector.length > MAX_SELECTOR ||
                selector.indexOf('\r') >= 0 ||
                selector.indexOf('\n') >= 0
        ) {
            throw ApiException.badRequest("selector must be 1..$MAX_SELECTOR characters")
        }
        return selector
    }

    private suspend fun evaluateIn(
        cdp: CdpSession,
        expression: String,
        awaitPromise: Boolean,
    ): EvalResult {
        val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
        val frameId =
            loaded?.frameTree?.frame?.id
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a frame")
        val contextId =
            cdp.call(
                    "Page.createIsolatedWorld",
                    buildJsonObject {
                        put("frameId", frameId)
                        put("worldName", ISOLATED_WORLD)
                    },
                )
                .decodeCdp<WorldResult>()
                ?.executionContextId
                ?: throw ApiException(502, "cdp_error", "Chrome did not return an isolated world")
        return evalResult(
            cdp.call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("contextId", contextId)
                    put("returnByValue", true)
                    put("awaitPromise", awaitPromise)
                },
            )
        )
    }

    private fun evalResult(payload: JsonObject): EvalResult {
        val parsed = payload.decodeCdp<EvalPayload>() ?: return EvalResult()
        val details = parsed.exceptionDetails
        if (details != null) return EvalResult(exception = details.text ?: "exception")
        val remote = parsed.result ?: return EvalResult()
        return EvalResult(type = remote.type, value = remote.value)
    }

    private suspend fun collectLogs(session: CdpSession, timeoutMs: Long): ConsoleResult {
        val entries = Collections.synchronizedList(mutableListOf<ConsoleEntry>())
        coroutineScope {
            val job = launch { session.events.collect { event -> appendLog(entries, event) } }
            try {
                session.call("Log.enable")
                delay(timeoutMs)
            } finally {
                job.cancel()
            }
        }
        return ConsoleResult(synchronized(entries) { entries.toList() })
    }

    private fun appendLog(entries: MutableList<ConsoleEntry>, event: CdpEvent) {
        if (event.method != "Log.entryAdded") return
        val entry = event.params.decodeCdp<LogAdded>()?.entry ?: return
        val text = entry.text ?: return
        entries += ConsoleEntry(entry.level ?: "info", text)
    }

    private fun nodeRef(ref: String): String {
        val trimmed = ref.trim().removeSurrounding("[", "]")
        if (!NODE_REF.matches(trimmed)) {
            throw ApiException.badRequest("ref must be a node id from the browser snapshot")
        }
        return trimmed
    }

    private suspend fun <T> useSocket(
        name: String,
        block: suspend (InputStream, OutputStream) -> T,
    ): T =
        withContext(io) {
            open(name).use { socket ->
                try {
                    block(socket.input, socket.output)
                } catch (e: SocketTimeoutException) {
                    throw ApiException(
                        503,
                        "cdp_timeout",
                        e.message ?: "DevTools did not answer in time",
                        cause = e,
                    )
                } catch (e: IOException) {
                    throw ApiException(
                        502,
                        "cdp_error",
                        e.message ?: "DevTools connection failed",
                        cause = e,
                    )
                }
            }
        }

    private suspend fun socketList(): List<HelperSocket> =
        withContext(io) {
            val json =
                try {
                    listSockets()
                } catch (e: RemoteException) {
                    throw ApiException(503, "helper_error", e.message.orEmpty(), cause = e)
                }
            try {
                ApiJson.decodeFromString(serializer<List<HelperSocket>>(), json)
            } catch (e: SerializationException) {
                throw ApiException(
                    503,
                    "helper_error",
                    "The helper returned ${json.take(ERROR_SNIPPET)}",
                    cause = e,
                )
            }
        }

    private fun parseTargets(socket: HelperSocket, json: String): List<BrowserTarget> {
        val listed =
            try {
                ApiJson.decodeFromString(serializer<List<ListedTarget>>(), json)
            } catch (e: SerializationException) {
                throw ApiException(502, "cdp_error", "DevTools target list was not JSON", cause = e)
            }
        return listed
            .filter { it.type !in SKIPPED_TYPES }
            .map { item ->
                BrowserTarget(
                    id = browserTargetId(socket.name, item.id),
                    type = item.type,
                    title = item.title?.takeIf { it.isNotBlank() },
                    url = item.url?.takeIf { it.isNotBlank() },
                    socket = socket.name,
                    pid = socket.pid,
                    packageName = socket.packageName,
                )
            }
    }

    private fun webUrl(url: String): String {
        val trimmed = url.trim()
        if (!acceptable(trimmed))
            throw ApiException.badRequest("url must be http(s) or about:blank")
        return trimmed
    }

    private fun acceptable(url: String): Boolean {
        if (url.length > MAX_URL) return false
        if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) return false
        if (url == "about:blank") return true
        return url.startsWith("https://") || url.startsWith("http://")
    }

    @Serializable
    private data class HelperSocket(
        val name: String,
        val pid: Int? = null,
        @SerialName("package") val packageName: String? = null,
    )

    @Serializable
    private data class ListedTarget(
        val id: String,
        val type: String = "page",
        val title: String? = null,
        val url: String? = null,
    )

    @Serializable private data class CreatedTarget(val targetId: String? = null)

    @Serializable private data class NavigateResult(val errorText: String? = null)

    @Serializable private data class DocumentResult(val root: NodeId? = null)

    @Serializable private data class NodeId(val nodeId: Int = 0)

    @Serializable private data class QueryResult(val nodeId: Int = 0)

    @Serializable private data class WorldResult(val executionContextId: Int? = null)

    @Serializable
    private data class EvalPayload(
        val result: RemoteObject? = null,
        val exceptionDetails: ExceptionText? = null,
    )

    @Serializable
    private data class RemoteObject(val type: String? = null, val value: JsonElement? = null)

    @Serializable private data class ExceptionText(val text: String? = null)

    @Serializable private data class QuadResult(val quads: List<JsonElement> = emptyList())

    @Serializable private data class LogAdded(val entry: LogLine? = null)

    @Serializable private data class LogLine(val text: String? = null, val level: String? = null)

    private companion object {
        const val HTTP_SERVER_ERROR = 500
        val NODE_REF = Regex("[A-Za-z0-9_-]{1,64}")
        const val MAX_BODY = 4 * 1024 * 1024
        const val MAX_URL = 2_000
        const val MAX_SELECTOR = 1_000
        const val MAX_EXPRESSION = 20_000
        const val MAX_CONSOLE_MS = 5_000L
        const val ISOLATED_WORLD = "phoneapi"
        const val PAGE_ATTEMPTS = 4
        const val PAGE_RETRY_MS = 200L
        const val FRONT_SETTLE_MS = 200L
        const val ERROR_SNIPPET = 80
        val SKIPPED_TYPES = setOf("service_worker", "shared_worker", "worker")
    }

    private sealed interface TapTarget {
        data class Ref(val id: String) : TapTarget

        data class Css(val selector: String) : TapTarget
    }
}

@Serializable internal data class FrameTreeResult(val frameTree: FrameNode? = null)

@Serializable internal data class FrameNode(val frame: FrameInfo? = null)

@Serializable
internal data class FrameInfo(
    val id: String? = null,
    val url: String? = null,
    val parentId: String? = null,
)
