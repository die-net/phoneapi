package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Rect as ModelRect

/** Works out where a real finger can touch a node. */
class NodeTargeting(private val screen: () -> Rect) {

    /**
     * The largest part of [node] that is on screen and not covered by another window. Scrolls the
     * node into view once if needed. Throws `409 offscreen` or `409 obscured` (unless [force]).
     * Must be called off the main thread.
     */
    suspend fun touchTarget(
        node: AccessibilityNodeInfo,
        windows: List<AccessibilityWindowInfo>,
        force: Boolean,
    ): ModelRect {
        if (!node.isVisibleToUser) {
            node.performAction(AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            delay(SHOW_ON_SCREEN_SETTLE_MS)
            if (!node.refresh() || !node.isVisibleToUser) {
                NodeCompat.recycleAll(windows)
                throw offscreen()
            }
        }
        try {
            val layout = ScreenLayout.capture(windows, screen())
            val raw = Rect().also(node::getBoundsInScreen)
            val clipped = layout.clip(node.windowId, raw)
            if (clipped.isEmpty) throw offscreen()
            if (force) return clipped.toModel()
            val visible = layout.visibleRegion(node.windowId, clipped).largestRect()
            if (
                visible == null ||
                    visible.width() < MIN_TARGET_PX ||
                    visible.height() < MIN_TARGET_PX
            ) {
                val cover = layout.mainCover(node.windowId, clipped)
                val by =
                    cover?.let { "the ${windowTypeName(it.type)} window (w${it.id})" }
                        ?: "another window"
                throw ApiException(
                    409,
                    "obscured",
                    "The node is covered by $by. Hide the keyboard (POST /v1/ime/hide), scroll " +
                        "it into view, or pass force=true.",
                )
            }
            return visible.toModel()
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun offscreen() =
        ApiException(409, "offscreen", "The node is off screen and could not be scrolled into view")

    private companion object {
        const val SHOW_ON_SCREEN_SETTLE_MS = 350L
        const val MIN_TARGET_PX = 6
    }
}
