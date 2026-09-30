package net.die.phoneapi.a11y

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.NodeSelector
import net.die.phoneapi.model.SnapshotFormat
import net.die.phoneapi.model.UiNode
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UiWindow

/** Builds snapshots and runs node queries over the live accessibility tree. */
class SnapshotEngine(private val graph: AppGraph) {
    private data class Built(
        val options: SnapshotOptions,
        val seq: Long,
        val atMs: Long,
        val windows: List<UiWindow>,
        val truncated: Boolean,
    )

    private val mutex = Mutex()
    @Volatile private var cache: Built? = null

    /** Drops the cached snapshot; called after every action. */
    fun invalidate() {
        cache = null
    }

    suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        val service = graph.a11y.require()
        val key = options.copy(format = SnapshotFormat.COMPACT, autoWake = true)
        val built =
            withContext(graph.ioDispatcher) {
                mutex.withLock {
                    val seq = graph.uiTracker.seq.value
                    cache?.takeIf {
                        it.options == key &&
                            it.seq == seq &&
                            SystemClock.uptimeMillis() - it.atMs < CACHE_MS
                    } ?: build(service.windows, key, seq).also { cache = it }
                }
            }
        val state = graph.state.current
        val compact =
            if (options.format == SnapshotFormat.JSON) null
            else
                CompactFormatter.format(
                    CompactFormatter.header(built.seq, state, built.truncated),
                    built.windows,
                )
        val windows =
            if (options.format == SnapshotFormat.COMPACT) built.windows.map { it.copy(root = null) }
            else built.windows
        return UiSnapshot(built.seq, System.currentTimeMillis(), state, windows, compact)
    }

    suspend fun find(request: FindRequest): FindResult {
        val service = graph.a11y.require()
        val selector = request.selector
        val matcher = SelectorMatcher(selector)
        return withContext(graph.ioDispatcher) {
            val seq = graph.uiTracker.seq.value
            val ref = selector.ref
            if (ref != null) {
                return@withContext FindResult(
                    seq,
                    listOfNotNull(findByRef(service.windows, ref, matcher)),
                )
            }
            val wanted = selector.index?.let { it + 1 } ?: request.limit.coerceIn(1, MAX_FIND)
            val matches = mutex.withLock {
                search(service.windows, matcher, wanted, request.includeInvisible)
            }
            val picked = selector.index?.let { i -> listOfNotNull(matches.getOrNull(i)) } ?: matches
            FindResult(seq, picked)
        }
    }

    /**
     * The node [selector] points at, as a copy owned by the caller. Throws `404 not_found` when
     * nothing matches. Must be called off the main thread.
     */
    suspend fun resolve(selector: NodeSelector): AccessibilityNodeInfo {
        val ref =
            selector.ref?.takeIf { SelectorMatcher(selector).isEmpty }
                ?: find(FindRequest(selector, limit = 1)).matches.firstOrNull()?.ref
                ?: throw ApiException.notFound("No node matches the selector")
        return graph.nodes.acquire(ref)
    }

    private fun build(
        windows: List<AccessibilityWindowInfo>,
        options: SnapshotOptions,
        seq: Long,
    ): Built {
        val started = SystemClock.uptimeMillis()
        graph.nodes.beginSnapshot()
        try {
            val layout = ScreenLayout.capture(windows, graph.screenRect())
            val traversal = Traversal(layout, options)
            val out =
                windows
                    .asSequence()
                    .sortedByDescending { it.layer }
                    .filter { include(it, options, layout) }
                    .map { traversal.window(it, includeTree(it, options)) }
                    .toList()
            graph.nodes.endSnapshot()
            Log.d(
                TAG,
                "snapshot: ${traversal.count} nodes, ${out.size} windows in " +
                    "${SystemClock.uptimeMillis() - started} ms",
            )
            return Built(options, seq, SystemClock.uptimeMillis(), out, traversal.truncated)
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun include(
        w: AccessibilityWindowInfo,
        options: SnapshotOptions,
        layout: ScreenLayout,
    ): Boolean {
        options.windowId?.let {
            return w.id == it
        }
        if (options.allWindows) return true
        return when (w.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION,
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> true
            AccessibilityWindowInfo.TYPE_SYSTEM -> {
                val bounds = layout.window(w.id)?.bounds ?: return false
                w.isFocused ||
                    w.isActive ||
                    area(bounds) >= area(layout.screen) * INTERESTING_SYSTEM_FRACTION
            }
            else -> false
        }
    }

    private fun includeTree(w: AccessibilityWindowInfo, options: SnapshotOptions) =
        w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
            options.allWindows ||
            options.windowId != null

    private fun findByRef(
        windows: List<AccessibilityWindowInfo>,
        ref: String,
        matcher: SelectorMatcher,
    ): UiNode? {
        try {
            val layout = ScreenLayout.capture(windows, graph.screenRect())
            return graph.nodes.withNode(ref) { node ->
                NodeConverter(layout).convert(node, ref, emptyList()).takeIf {
                    matcher.matches(it, node.packageName?.toString())
                }
            }
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun search(
        windows: List<AccessibilityWindowInfo>,
        matcher: SelectorMatcher,
        wanted: Int,
        includeInvisible: Boolean,
    ): List<UiNode> {
        try {
            val layout = ScreenLayout.capture(windows, graph.screenRect())
            val search = Search(NodeConverter(layout), matcher, wanted, includeInvisible)
            windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .sortedWith(
                    compareBy<AccessibilityWindowInfo> { searchOrder(it.type) }
                        .thenByDescending { it.layer }
                )
                .forEach { window -> if (!search.done) window.root?.let(search::tree) }
            return search.out
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private inner class Search(
        private val converter: NodeConverter,
        private val matcher: SelectorMatcher,
        private val wanted: Int,
        private val includeInvisible: Boolean,
    ) {
        val out = ArrayList<UiNode>()
        val done: Boolean
            get() = out.size >= wanted

        fun tree(root: AccessibilityNodeInfo) {
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            var visited = 0
            while (stack.isNotEmpty() && !done && visited < MAX_NODES) {
                val node = stack.removeLast()
                visited++
                if (includeInvisible || node.isVisibleToUser) visit(node, stack)
                else NodeCompat.recycle(node)
            }
            stack.forEach(NodeCompat::recycle)
        }

        private fun visit(node: AccessibilityNodeInfo, stack: ArrayDeque<AccessibilityNodeInfo>) {
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            val converted = converter.convert(node, "", emptyList())
            if (matcher.matches(converted, node.packageName?.toString())) {
                out += converted.copy(ref = graph.nodes.register(node))
            } else {
                NodeCompat.recycle(node)
            }
        }
    }

    private fun searchOrder(type: Int) =
        when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> 0
            AccessibilityWindowInfo.TYPE_SYSTEM -> 1
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> 2
            else -> 3
        }

    private inner class Traversal(
        private val layout: ScreenLayout,
        private val options: SnapshotOptions,
    ) {
        private val converter = NodeConverter(layout)
        var count = 0
            private set

        var truncated = false
            private set

        fun window(w: AccessibilityWindowInfo, includeTree: Boolean): UiWindow {
            val geometry = layout.window(w.id)
            val root = if (includeTree) w.root else null
            val pkg =
                root?.packageName?.toString()
                    ?: graph.state.ime.packageName.takeIf {
                        w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                    }
            return UiWindow(
                id = w.id,
                type = windowTypeName(w.type),
                title = w.title?.toString(),
                packageName = pkg,
                layer = w.layer,
                focused = w.isFocused,
                active = w.isActive,
                bounds = (geometry?.bounds ?: Rect()).toModel(),
                root = root?.let { node(it, 0) },
            )
        }

        private fun node(n: AccessibilityNodeInfo, depth: Int): UiNode? {
            if (!options.includeInvisible && !n.isVisibleToUser) {
                NodeCompat.recycle(n)
                return null
            }
            if (count >= MAX_NODES) {
                truncated = true
                NodeCompat.recycle(n)
                return null
            }
            count++
            val ref = graph.nodes.register(n)
            val maxDepth = options.maxDepth
            val children =
                if (maxDepth != null && depth >= maxDepth) {
                    if (n.childCount > 0) truncated = true
                    emptyList()
                } else {
                    (0 until n.childCount).mapNotNull { i ->
                        n.getChild(i)?.let { node(it, depth + 1) }
                    }
                }
            return converter.convert(n, ref, children)
        }
    }

    private companion object {
        const val TAG = "PhoneApiUi"
        const val CACHE_MS = 5_000L
        const val MAX_NODES = 5_000
        const val MAX_FIND = 200
        const val INTERESTING_SYSTEM_FRACTION = 0.15
    }
}

/** Converts platform nodes to wire nodes, including occlusion by windows stacked above. */
internal class NodeConverter(private val layout: ScreenLayout) {
    private val scratch = Rect()

    fun convert(n: AccessibilityNodeInfo, ref: String, children: List<UiNode>): UiNode {
        n.getBoundsInScreen(scratch)
        val clipped = layout.clip(n.windowId, scratch)
        val bounds = if (clipped.isEmpty) Rect(scratch) else clipped
        val obscured =
            !clipped.isEmpty &&
                layout.coverAt(n.windowId, clipped.centerX(), clipped.centerY()) != null
        val className = n.className?.toString()
        val role =
            Roles.roleOf(
                RoleHints(
                    className = className,
                    roleDescription = n.extras?.getCharSequence(ROLE_DESCRIPTION_KEY)?.toString(),
                    editable = n.isEditable,
                    checkable = n.isCheckable,
                    clickable = n.isClickable,
                    scrollable = n.isScrollable,
                    heading = n.isHeading,
                    collection = n.collectionInfo != null,
                    collectionItem = n.collectionItemInfo != null,
                )
            )
        val showingHint = n.isShowingHintText
        return UiNode(
            ref = ref,
            role = role,
            className = className,
            text = if (showingHint) null else n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            id = shortId(n.viewIdResourceName, n.packageName?.toString()),
            bounds = bounds.toModel(),
            states = states(n, obscured),
            actions = ActionCatalog.names(n),
            children = children,
        )
    }

    private fun states(n: AccessibilityNodeInfo, obscured: Boolean): List<String> {
        val states = STATE_TESTS.mapNotNull { (name, test) -> name.takeIf { test(n) } }
        return if (obscured) states + UiStates.OBSCURED else states
    }

    private fun shortId(id: String?, pkg: String?): String? {
        if (id == null || pkg == null) return id
        val prefix = "$pkg:id/"
        return if (id.startsWith(prefix)) id.substring(prefix.length) else id
    }

    private companion object {
        /** Where androidx and Compose store a node's role description. */
        const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"

        val STATE_TESTS: List<Pair<String, (AccessibilityNodeInfo) -> Boolean>> =
            listOf(
                UiStates.CLICKABLE to AccessibilityNodeInfo::isClickable,
                UiStates.LONG_CLICKABLE to AccessibilityNodeInfo::isLongClickable,
                UiStates.CHECKABLE to AccessibilityNodeInfo::isCheckable,
                UiStates.CHECKED to NodeCompat::isChecked,
                UiStates.SELECTED to AccessibilityNodeInfo::isSelected,
                UiStates.FOCUSED to AccessibilityNodeInfo::isFocused,
                UiStates.FOCUSABLE to AccessibilityNodeInfo::isFocusable,
                UiStates.DISABLED to { n -> !n.isEnabled },
                UiStates.EDITABLE to AccessibilityNodeInfo::isEditable,
                UiStates.PASSWORD to AccessibilityNodeInfo::isPassword,
                UiStates.SCROLLABLE to AccessibilityNodeInfo::isScrollable,
            )
    }
}

internal fun windowTypeName(type: Int): String =
    when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        TYPE_MAGNIFICATION_OVERLAY -> "magnification"
        TYPE_WINDOW_CONTROL -> "window_control"
        else -> "unknown"
    }

/** The connected accessibility service, or `503 accessibility_unavailable`. */
internal fun StateFlow<PhoneAccessibilityService?>.require(): PhoneAccessibilityService =
    value ?: throw ApiException.a11yUnavailable()

// Values of AccessibilityWindowInfo constants added after minSdk; the ints are stable.
private const val TYPE_MAGNIFICATION_OVERLAY = 6
private const val TYPE_WINDOW_CONTROL = 7
