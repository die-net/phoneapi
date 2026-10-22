package net.die.phoneapi.browser

import android.os.RemoteException
import java.io.InputStream
import java.io.OutputStream
import java.util.LinkedHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.input.SwipeSpec
import net.die.phoneapi.input.fallbackTouchscreen
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserInput
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
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.TimedPoint

/**
 * Talks to Chrome and WebView over an adb DevTools stream. One service call keeps a single
 * connection. The last accessibility tree for a target is kept so a later tap can reuse a snapshot
 * ref without fetching the tree again.
 */
internal sealed interface TapTarget {
    data class Ref(val id: String) : TapTarget

    data class Css(val selector: String) : TapTarget
}

internal class BrowserServiceImpl(
    private val io: CoroutineContext,
    private val listSockets: suspend () -> String,
    private val open: suspend (String) -> DevtoolsSocket,
    private val websocketKey: () -> String = ::websocketKey,
    /** Opens a DevTools socket with a short read timeout, for [TabProbe]. Null probes nothing. */
    openProbe: (suspend (String) -> DevtoolsSocket)? = null,
    private val contentBounds: suspend (String?) -> Rect = { Rect(0, 0, 0, 0) },
    private val touchAt: suspend (Rect, Boolean) -> ActionResult = { _, _ ->
        ActionResult(ok = false)
    },
    private val swipeAt: suspend (Point, Point, SwipeSpec) -> ActionResult = { _, _, _ ->
        ActionResult(ok = false)
    },
    private val gestureAt: suspend (List<List<TimedPoint>>, Boolean) -> ActionResult = { _, _ ->
        ActionResult(ok = false)
    },
    private val pressKey: suspend (KeyRequest) -> ActionResult = { ActionResult(ok = false) },
    private val typeText: suspend (TextRequest) -> ActionResult = { ActionResult(ok = false) },
    private val targetTimeoutMs: Long = TARGET_LIST_MS,
    private val axTreeCap: Int = AX_TREE_CACHE_CAP,
    private val prepare: suspend (Boolean) -> Boolean = { false },
    private val sequence: () -> Long = { 0L },
    private val invalidateSnapshots: () -> Unit = {},
    private val showPackage: suspend (String) -> Unit = {},
    private val inFront: suspend (String) -> Boolean = { true },
    /** Readability.js, page-pending.js, then page-read.js. */
    private val pageScript: suspend () -> String = { "" },
    /** page-pending.js, for snapshots. */
    private val pendingScript: suspend () -> String = { "" },
    private val devtoolsGap: (BrowserGap) -> String = { gap ->
        browserGapMessage(gap, chromeInstalled = true, usbDebugging = true)
    },
    private val refreshHz: () -> Float = { 60f },
    private val touchscreen: suspend () -> TouchscreenInfo = { fallbackTouchscreen() },
) : BrowserService {
    private val axTrees = newAxCache()
    private val probe = openProbe?.let { TabProbe(io, it, websocketKey) }
    @Volatile private var pageSource: String? = null
    @Volatile private var stateSource: String? = null
    private val pageInput: PageInput by lazy {
        PageInput(
            touchAt = touchAt,
            swipeAt = swipeAt,
            gestureAt = gestureAt,
            pressKey = pressKey,
            typeText = typeText,
            contentOnScreen = { pkg -> contentOnScreen(pkg) },
            quads = { cdp, id, wanted -> elementQuads(cdp, id, wanted) },
            focusNode = { cdp, id, wanted -> focusParams(cdp, id, wanted) },
            target = { ref, selector -> optionalTarget(ref, selector) },
            refreshHz = refreshHz,
            touchscreen = touchscreen,
        )
    }

    private fun newAxCache(): AxTreeCache = AxTreeCache(axTreeCap)

    override suspend fun targets(): List<BrowserTarget> {
        val sockets = socketList()
        if (sockets.isEmpty()) {
            throw ApiException.unavailable("browser_unavailable", devtoolsGap(BrowserGap.NONE))
        }
        val found = queryTargets(sockets)
        axTrees.retain(found.map { it.id }.toSet())
        return found
    }

    private suspend fun queryTargets(sockets: List<HelperSocket>): List<BrowserTarget> =
        coroutineScope {
            val results = sockets.map { socket -> async { listQuietly(socket) } }.awaitAll()
            val out = ArrayList<BrowserTarget>()
            val failures = ArrayList<ApiException>()
            for (result in results) {
                when (result) {
                    is SocketList.Ready -> out += result.targets
                    is SocketList.Failed -> failures += result.error
                }
            }
            if (out.isEmpty() && failures.isNotEmpty()) {
                throw ApiException(
                    failures.last().status,
                    "cdp_error",
                    failures.joinToString("; ") { it.message ?: it.error },
                )
            }
            out
        }

    private suspend fun listQuietly(socket: HelperSocket): SocketList =
        try {
            SocketList.Ready(listOne(socket))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.error == "helper_unavailable") throw e
            SocketList.Failed(e)
        }

    override suspend fun openTab(url: String): BrowserTarget {
        val pageUrl = webUrl(url)
        val chrome =
            socketList().firstOrNull { it.name == CHROME_SOCKET }
                ?: throw ApiException.unavailable(
                    "browser_unavailable",
                    devtoolsGap(BrowserGap.CHROME),
                )
        // Chrome on Android ignores createTarget's background flag and switches to a new tab
        // while it is in front, so say whether this call took the screen.
        val tookScreen = browserPackage(chrome, chrome.name)?.let { inFront(it) } == true
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
            tookScreen = tookScreen,
        )
    }

    override suspend fun navigate(id: String, url: String): BrowserTarget {
        val pageUrl = webUrl(url)
        val (socket, chromeId) = parseBrowserTargetId(id)
        axTrees.remove(id)
        usePage(socket, chromeId, repeatable = true) { cdp ->
            val error =
                cdp.call("Page.navigate", buildJsonObject { put("url", pageUrl) })
                    .decodeCdp<NavigateResult>()
                    ?.errorText
            if (!error.isNullOrBlank()) throw ApiException.badRequest(error)
        }
        return BrowserTarget(id = id, type = "page", url = pageUrl, socket = socket)
    }

    override suspend fun closeTab(id: String): ActionResult {
        val (socket, chromeId) = parseBrowserTargetId(id)
        val closed =
            useCdp(socket, "/devtools/browser") { cdp ->
                cdp.call("Target.closeTarget", buildJsonObject { put("targetId", chromeId) })
            }
        axTrees.remove(id)
        val ok = closed.decodeCdp<ClosedTarget>()?.success != false
        return ActionResult(ok = ok, backend = "cdp", seq = sequence())
    }

    override suspend fun activate(id: String) {
        val (socket, chromeId) = parseBrowserTargetId(id)
        useCdp(socket, "/devtools/browser") { cdp ->
            cdp.call("Target.activateTarget", buildJsonObject { put("targetId", chromeId) })
        }
    }

    override suspend fun showing(id: String): Boolean {
        val (socket, chromeId) = parseBrowserTargetId(id)
        return try {
            withTimeoutOrNull(SHOWING_MS) { usePage(socket, chromeId) { tabShowing(it) } } == true
        } catch (_: ApiException) {
            // Closed, or not answering: either way, not showing.
            false
        }
    }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult {
        lateinit var wanted: TapTarget
        return act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { wanted = tapTarget(request) },
        ) { cdp, pkg ->
            pageInput.tap(cdp, id, wanted, pkg, request)
        }
    }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { pageInput.checkSwipe(request) },
        ) { cdp, pkg ->
            pageInput.swipe(cdp, id, pkg, request)
        }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        act(
            id,
            request.autoWake,
            request.input,
            pointer = true,
            before = { pageInput.checkGesture(request) },
        ) { cdp, pkg ->
            pageInput.gesture(cdp, pkg, request)
        }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult {
        var pageKey: DomKey? = null
        return act(
            id,
            request.autoWake,
            request.input,
            before = { pageKey = pageInput.prepareKey(request) },
        ) { cdp, _ ->
            pageInput.key(cdp, request, pageKey)
        }
    }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult {
        var focus: TapTarget? = null
        return act(
            id,
            request.autoWake,
            request.input,
            before = { focus = pageInput.prepareText(request) },
        ) { cdp, pkg ->
            pageInput.text(cdp, id, pkg, request, focus)
        }
    }

    private suspend fun focusParams(cdp: CdpSession, id: String, target: TapTarget): JsonObject =
        when (target) {
            is TapTarget.Ref ->
                backendParams(cachedBackend(id, target.id) ?: fetchBackend(cdp, id, target.id))
            is TapTarget.Css -> cssNode(cdp, target.selector)
        }

    private suspend fun act(
        id: String,
        autoWake: Boolean,
        input: BrowserInput,
        pointer: Boolean = false,
        before: () -> Unit = {},
        block: suspend (CdpSession, String?) -> ActionResult,
    ): ActionResult {
        // A cdp event reaches the tab with the screen off, so only touch input wakes it.
        // Callers resolve AUTO; one that reaches here is touch.
        val woke = prepare(autoWake && input in TOUCH_INPUTS)
        try {
            before()
            val (socket, chromeId) = parseBrowserTargetId(id)
            val pkg =
                if (input == BrowserInput.CDP_MOUSE) {
                    null
                } else {
                    browserPackage(socketList().firstOrNull { it.name == socket }, socket)
                }
            if (pointer && input == BrowserInput.CDP) requireOnScreen(pkg)
            val result =
                usePage(socket, chromeId) { cdp ->
                    probe?.revive(cdp, socket, reading = false)
                    // Touch brings the tab on screen, which wakes it.
                    if (input !in TOUCH_INPUTS) probe?.requireRunning(cdp, socket)
                    if (pointer && input == BrowserInput.CDP) requireShowing(cdp)
                    block(cdp, pkg)
                }
            return result.copy(woke = woke, seq = sequence())
        } finally {
            invalidateSnapshots()
        }
    }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult {
        val expression = request.expression
        if (expression.isBlank() || expression.length > MAX_EXPRESSION) {
            throw ApiException.badRequest("expression must be 1..$MAX_EXPRESSION characters")
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        return usePage(socket, chromeId) { cdp ->
            probe?.revive(cdp, socket, reading = false)
            evaluateIn(cdp, expression, request.awaitPromise)
        }
    }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult {
        checkRead(request)
        if (request.show) show(id)
        val extract = extractPage(id, request.scope, request.selector)
        val socket = parseBrowserTargetId(id).first
        return readPage(extract, request)
            .copy(shown = drawn(socket, extract.visibility), reloaded = extract.reloaded)
    }

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult {
        checkFind(request)
        val extract = extractPage(id, request.scope, request.selector)
        return findInPage(extract, request).copy(reloaded = extract.reloaded)
    }

    private suspend fun extractPage(id: String, scope: ReadScope, selector: String?): PageExtract {
        val script =
            readScript(pageSource ?: pageScript().also { pageSource = it }, scope, selector)
        val (socket, chromeId) = parseBrowserTargetId(id)
        var reloaded = false
        val result =
            usePage(socket, chromeId, repeatable = true) { cdp ->
                reloaded = probe?.revive(cdp, socket, reading = true) == true || reloaded
                evaluateIn(cdp, script, awaitPromise = false)
            }
        return decodeExtract(result, selector).copy(reloaded = reloaded)
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

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot {
        if (options.maxNodes !in 1..MAX_SNAPSHOT_NODES || (options.maxChars ?: 1) < 1) {
            throw ApiException.badRequest(
                "maxNodes must be 1 to $MAX_SNAPSHOT_NODES, and maxChars at least 1"
            )
        }
        val (socket, chromeId) = parseBrowserTargetId(id)
        if (options.show) show(id)
        var reloaded = false
        val (snapshot, state) =
            usePage(socket, chromeId, repeatable = true) { cdp ->
                reloaded = probe?.revive(cdp, socket, reading = true) == true || reloaded
                val loaded = cdp.call("Page.getFrameTree").decodeCdp<FrameTreeResult>()
                val url = loaded?.frameTree?.frame?.url
                cdp.call("Accessibility.enable")
                val tree = cdp.call("Accessibility.getFullAXTree")
                axTrees.put(id, tree)
                val text = formatAxTree(tree, url, options)
                val script = stateSource ?: stateScript(pendingScript()).also { stateSource = it }
                val state = pageState(cdp, loaded?.frameTree?.frame?.id, script)
                BrowserSnapshot(
                    id = id,
                    url = url,
                    title = text.title,
                    compact = text.compact,
                    pending = state.pending,
                ) to state
            }
        return snapshot.copy(shown = drawn(socket, state.visibility), reloaded = reloaded)
    }

    /**
     * Brings a tab on screen, as touch input does, then waits until its text stops growing, so
     * content that loads only when drawn has a chance to.
     *
     * Chrome coming forward selects a tab opened while it was in the background, and that can land
     * after this selects the wanted one. So the tab has to still be showing once its text holds
     * still, or this tries again.
     */
    private suspend fun show(id: String) {
        val (socket, chromeId) = parseBrowserTargetId(id)
        prepare(true)
        val pkg = browserPackage(socketList().firstOrNull { it.name == socket }, socket)
        try {
            usePage(socket, chromeId) { cdp ->
                var front = pkg == null || inFront(pkg)
                if (!front) {
                    showPackage(pkg.orEmpty())
                    delay(APPEAR_MS)
                    front = inFront(pkg.orEmpty())
                }
                // With the app behind, visibility says nothing, so there is no point trying again.
                bringForward(cdp, attempts = if (front) SHOW_ATTEMPTS else 1)
            }
        } finally {
            invalidateSnapshots()
        }
    }

    /**
     * Whether the tab is being drawn. A tab can report `visible` while Chrome itself is hidden,
     * such as one opened while Chrome was in the background, so a visible tab also needs its app in
     * front. Null when that cannot be told.
     */
    private suspend fun drawn(socket: String, visibility: String?): Boolean? {
        if (visibility == null) return null
        if (visibility != "visible") return false
        val pkg = browserPackage(socketList().firstOrNull { it.name == socket }, socket)
        return pkg?.let { inFront(it) }
    }

    private suspend fun listOne(socket: HelperSocket): List<BrowserTarget> {
        if (socket.name != CHROME_SOCKET) {
            return listJsonTargets(
                devtools = withContext(io) { open(socket.name) },
                socket.name,
                socket.pid,
                socket.packageName,
                io,
                targetTimeoutMs,
            )
        }
        val result = useCdp(socket.name, "/devtools/browser") { it.call("Target.getTargets") }
        return chromeTargets(result, socket.name, socket.pid, socket.packageName)
    }

    /**
     * Runs [block] on a page, retrying a server error. Once [block] has started, only a
     * [repeatable] block is retried, so a tap, text, or script never runs twice.
     */
    private suspend fun <T> usePage(
        socket: String,
        chromeId: String,
        repeatable: Boolean = false,
        block: suspend (CdpSession) -> T,
    ): T {
        var last: ApiException? = null
        repeat(PAGE_ATTEMPTS) { attempt ->
            var started = false
            try {
                return useCdp(socket, "/devtools/page/$chromeId") { cdp ->
                    started = true
                    block(cdp)
                }
            } catch (e: ApiException) {
                if (!retryable(e, socket, started, repeatable)) throw e
                last = e
                if (attempt < PAGE_ATTEMPTS - 1) delay(PAGE_RETRY_MS)
            }
        }
        throw last ?: ApiException(502, "cdp_error", "DevTools did not answer")
    }

    private suspend fun retryable(
        e: ApiException,
        socket: String,
        started: Boolean,
        repeatable: Boolean,
    ): Boolean {
        if (e.status < HTTP_SERVER_ERROR || e.error in FINAL_ERRORS) return false
        if (started) return repeatable
        // A page that never answered: if Chrome as a whole does not, retrying is no use.
        if (e.error == "cdp_timeout" && probe?.frozen(socket) == true) throw browserFrozen()
        return true
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
        val tree = axTrees.get(targetId) ?: return null
        return try {
            elementBackendId(tree, ref)
        } catch (_: ApiException) {
            null
        }
    }

    private suspend fun fetchBackend(cdp: CdpSession, targetId: String, ref: String): Int {
        cdp.call("Accessibility.enable")
        val tree = cdp.call("Accessibility.getFullAXTree")
        axTrees.put(targetId, tree)
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

    private fun tapTarget(request: BrowserTapRequest): TapTarget =
        optionalTarget(request.ref, request.selector)
            ?: throw ApiException.badRequest("Pass a ref or a CSS selector")

    private fun optionalTarget(ref: String?, selector: String?): TapTarget? {
        val css = selector?.trim()?.ifEmpty { null }
        val id = ref?.trim()?.ifEmpty { null }
        if (css != null && id != null) {
            throw ApiException.badRequest("Pass a ref or a CSS selector, not both")
        }
        if (css != null) return TapTarget.Css(cssSelector(css))
        if (id != null) return TapTarget.Ref(nodeRef(id))
        return null
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

    private suspend fun contentOnScreen(pkg: String?): Rect {
        try {
            return contentBounds(pkg)
        } catch (e: ApiException) {
            if (e.error != "content_unavailable" || pkg.isNullOrEmpty()) throw e
            showPackage(pkg)
            delay(APPEAR_MS)
            return contentBounds(pkg)
        }
    }

    /**
     * Chrome holds a touch for a hidden tab and plays it once Chrome shows, so a cdp touch is
     * refused up front rather than left to land later. [requireShowing] covers a background tab
     * while Chrome is on screen.
     */
    private suspend fun requireOnScreen(pkg: String?) {
        if (pkg == null || inFront(pkg)) return
        throw ApiException(
            409,
            "not_visible",
            "$pkg is not on screen, and Chrome only takes cdp touch input while it is. " +
                "Use input touch, input cdp-mouse for a tap, or browser_eval.",
        )
    }

    private fun browserPackage(socket: HelperSocket?, name: String): String? =
        socket?.packageName ?: if (name == CHROME_SOCKET) CHROME_PACKAGE else null

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
    ): T = withContext(io) { open(name).use { socket -> socket.withStreams(block) } }

    private suspend fun socketList(): List<HelperSocket> =
        withContext(io) {
            val json =
                try {
                    listSockets()
                } catch (e: RemoteException) {
                    throw ApiException.helperDropped(e)
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

    private companion object {
        const val HTTP_SERVER_ERROR = 500
        /** Server errors that trying again cannot fix. */
        val FINAL_ERRORS = setOf("helper_unavailable", "browser_frozen")
        val NODE_REF = Regex("[A-Za-z0-9_-]{1,64}")
        const val MAX_SELECTOR = 1_000
        const val MAX_EXPRESSION = 20_000
        const val MAX_CONSOLE_MS = 5_000L
        const val MAX_SNAPSHOT_NODES = 5_000
        const val TARGET_LIST_MS = MAX_CONSOLE_MS
        const val PAGE_ATTEMPTS = 4
        const val PAGE_RETRY_MS = 200L
        const val APPEAR_MS = 500L
        const val SHOWING_MS = 2_000L
        const val SHOW_ATTEMPTS = 3
        const val ERROR_SNIPPET = 80
    }

    private sealed interface SocketList {
        data class Ready(val targets: List<BrowserTarget>) : SocketList

        data class Failed(val error: ApiException) : SocketList
    }
}

@Serializable
private data class HelperSocket(
    val name: String,
    val pid: Int? = null,
    @SerialName("package") val packageName: String? = null,
)

@Serializable private data class CreatedTarget(val targetId: String? = null)

@Serializable private data class ClosedTarget(val success: Boolean? = null)

@Serializable private data class NavigateResult(val errorText: String? = null)

@Serializable private data class DocumentResult(val root: NodeId? = null)

@Serializable private data class NodeId(val nodeId: Int = 0)

@Serializable private data class QueryResult(val nodeId: Int = 0)

@Serializable private data class QuadResult(val quads: List<JsonElement> = emptyList())

internal const val AX_TREE_CACHE_CAP = 16

/** Input that brings the tab forward and injects hardware events. */
internal val TOUCH_INPUTS = setOf(BrowserInput.TOUCH, BrowserInput.AUTO)

private class AxTreeCache(cap: Int) {
    private val limit = cap.coerceAtLeast(1)
    private val map = LinkedHashMap<String, JsonObject>(limit, LOAD, true)

    @Synchronized fun get(id: String): JsonObject? = map[id]

    @Synchronized
    fun put(id: String, tree: JsonObject) {
        map[id] = tree
        trim()
    }

    @Synchronized
    fun remove(id: String) {
        map.remove(id)
    }

    @Synchronized
    fun retain(live: Set<String>) {
        map.keys.retainAll(live)
        trim()
    }

    private fun trim() {
        while (map.size > limit) {
            map.remove(map.entries.iterator().next().key)
        }
    }

    private companion object {
        const val LOAD = 0.75f
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
