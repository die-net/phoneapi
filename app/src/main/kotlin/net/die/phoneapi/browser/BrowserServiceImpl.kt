package net.die.phoneapi.browser

import android.os.RemoteException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
 * Talks to Chrome and WebView over the helper's DevTools file descriptor. Each call opens a socket,
 * speaks HTTP or one CDP session, and closes it.
 */
internal class BrowserServiceImpl(
    private val io: CoroutineContext,
    private val listSockets: suspend () -> String,
    private val open: (String) -> DevtoolsSocket,
    private val websocketKey: () -> String = ::websocketKey,
    private val websocketMask: () -> ByteArray = ::websocketMask,
    private val contentBounds: suspend (String?) -> Rect = { Rect(0, 0, 0, 0) },
    private val touchAt: suspend (Rect, Boolean, InputBackend) -> ActionResult = { _, _, _ ->
        ActionResult(ok = false)
    },
) : BrowserService {
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
            useSocket(chrome.name) { input, output ->
                val cdp = CdpConnection(input, output, websocketKey, websocketMask)
                cdp.handshake("/devtools/browser")
                cdp.call("Target.createTarget", buildJsonObject { put("url", pageUrl) })
            }
        val chromeId =
            (created["targetId"] as? JsonPrimitive)?.contentOrNull
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
        usePage(socket, chromeId) { cdp ->
            val result = cdp.call("Page.navigate", buildJsonObject { put("url", pageUrl) })
            val error = (result["errorText"] as? JsonPrimitive)?.contentOrNull
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
            val quads = elementQuads(cdp, wanted)
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
        return useSocket(socket) { input, output ->
            val ws = WebSocketClient(input, output, websocketKey, websocketMask)
            ws.handshake("/devtools/page/$chromeId")
            collectLogs(ws, request.timeoutMs)
        }
    }

    override suspend fun snapshot(id: String): BrowserSnapshot {
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp ->
            val url = frameUrl(cdp.call("Page.getFrameTree"))
            cdp.call("Accessibility.enable")
            val tree = formatAxTree(cdp.call("Accessibility.getFullAXTree"), url)
            BrowserSnapshot(id = id, url = url, title = tree.title, compact = tree.compact)
        }
    }

    private suspend fun listOne(socket: HelperSocket): List<BrowserTarget> =
        useSocket(socket.name) { input, output ->
            output.write(httpRequest("GET", "/json/list"))
            output.flush()
            val response = input.readHttpResponse(MAX_BODY)
            if (response.status != HTTP_OK) {
                throw ApiException(
                    502,
                    "cdp_error",
                    "DevTools list returned HTTP ${response.status}",
                )
            }
            parseTargets(socket, response.body.decodeToString())
        }

    private suspend fun <T> usePage(
        socket: String,
        chromeId: String,
        block: suspend (CdpConnection) -> T,
    ): T {
        var last: ApiException? = null
        repeat(PAGE_ATTEMPTS) { attempt ->
            try {
                return useSocket(socket) { input, output ->
                    val cdp = CdpConnection(input, output, websocketKey, websocketMask)
                    cdp.handshake("/devtools/page/$chromeId")
                    block(cdp)
                }
            } catch (e: ApiException) {
                if (e.status < HTTP_SERVER_ERROR || e.error == "helper_unavailable") throw e
                last = e
                if (attempt < PAGE_ATTEMPTS - 1) delay(PAGE_RETRY_MS)
            }
        }
        throw last ?: ApiException(502, "cdp_error", "DevTools did not answer")
    }

    private fun elementQuads(cdp: CdpConnection, target: TapTarget): JsonArray {
        val params =
            when (target) {
                is TapTarget.Ref -> axNode(cdp, target.id)
                is TapTarget.Css -> cssNode(cdp, target.selector)
            }
        cdp.call("DOM.scrollIntoViewIfNeeded", params)
        val quads = cdp.call("DOM.getContentQuads", params)["quads"] as? JsonArray
        if (quads == null || quads.isEmpty()) {
            throw ApiException(409, "offscreen", "The node has no box on the page")
        }
        return quads
    }

    private fun axNode(cdp: CdpConnection, ref: String): JsonObject {
        cdp.call("Accessibility.enable")
        val backend = elementBackendId(cdp.call("Accessibility.getFullAXTree"), ref)
        return buildJsonObject { put("backendNodeId", backend) }
    }

    private fun cssNode(cdp: CdpConnection, selector: String): JsonObject {
        val doc = cdp.call("DOM.getDocument", buildJsonObject { put("depth", 0) })
        val root =
            ((doc["root"] as? JsonObject)?.get("nodeId") as? JsonPrimitive)?.intOrNull
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a document")
        val found =
            cdp.call(
                "DOM.querySelector",
                buildJsonObject {
                    put("nodeId", root)
                    put("selector", selector)
                },
            )
        val nodeId = (found["nodeId"] as? JsonPrimitive)?.intOrNull ?: 0
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

    private fun evaluateIn(
        cdp: CdpConnection,
        expression: String,
        awaitPromise: Boolean,
    ): EvalResult {
        val tree = cdp.call("Page.getFrameTree")
        val frame = (tree["frameTree"] as? JsonObject)?.get("frame") as? JsonObject
        val frameId =
            (frame?.get("id") as? JsonPrimitive)?.contentOrNull
                ?: throw ApiException(502, "cdp_error", "Chrome did not return a frame")
        val world =
            cdp.call(
                "Page.createIsolatedWorld",
                buildJsonObject {
                    put("frameId", frameId)
                    put("worldName", ISOLATED_WORLD)
                },
            )
        val contextId =
            (world["executionContextId"] as? JsonPrimitive)?.intOrNull
                ?: throw ApiException(502, "cdp_error", "Chrome did not return an isolated world")
        val result =
            cdp.call(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", expression)
                    put("contextId", contextId)
                    put("returnByValue", true)
                    put("awaitPromise", awaitPromise)
                },
            )
        return evalResult(result)
    }

    private fun evalResult(payload: JsonObject): EvalResult {
        val details = payload["exceptionDetails"] as? JsonObject
        if (details != null) {
            val text = (details["text"] as? JsonPrimitive)?.contentOrNull ?: "exception"
            return EvalResult(exception = text)
        }
        val remote = payload["result"] as? JsonObject ?: return EvalResult()
        return EvalResult(
            type = (remote["type"] as? JsonPrimitive)?.contentOrNull,
            value = remote["value"],
        )
    }

    private suspend fun collectLogs(socket: WebSocketClient, timeoutMs: Long): ConsoleResult {
        val session = CdpSession(socket)
        val entries = Collections.synchronizedList(mutableListOf<ConsoleEntry>())
        session.onEvent { method, params -> appendLog(entries, method, params) }
        session.start()
        session.call("Log.enable")
        delay(timeoutMs)
        return ConsoleResult(synchronized(entries) { entries.toList() })
    }

    private fun appendLog(
        entries: MutableList<ConsoleEntry>,
        method: String,
        params: JsonObject,
    ) {
        if (method != "Log.entryAdded") return
        val entry = params["entry"] as? JsonObject ?: return
        val text = (entry["text"] as? JsonPrimitive)?.contentOrNull ?: return
        val level = (entry["level"] as? JsonPrimitive)?.contentOrNull ?: "info"
        entries += ConsoleEntry(level, text)
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

    private fun frameUrl(tree: JsonObject): String? {
        val frame = (tree["frameTree"] as? JsonObject)?.get("frame") as? JsonObject ?: return null
        return (frame["url"] as? JsonPrimitive)?.contentOrNull
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

    private companion object {
        const val HTTP_OK = 200
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
