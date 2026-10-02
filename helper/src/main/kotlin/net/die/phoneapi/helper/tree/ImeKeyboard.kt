package net.die.phoneapi.helper.tree

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.die.phoneapi.model.TreeKey

/** Reads the on-screen keyboard's keys from the IME window's accessibility tree. */
internal object ImeKeyboard {
    private const val MAX_NODES = 600
    private const val MIN_KEY_PX = 16

    /** The IME's tappable keys, or an empty list if no IME window is showing. Blocking IPC. */
    fun scan(windows: List<AccessibilityWindowInfo>): List<TreeKey> {
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val root = ime?.root ?: return emptyList()
        return collect(root)
    }

    private fun collect(root: AccessibilityNodeInfo): List<TreeKey> {
        val keys = ArrayList<TreeKey>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val bounds = Rect()
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (node.isVisibleToUser) {
                labelOf(node)?.let { label ->
                    node.getBoundsInScreen(bounds)
                    if (bounds.width() >= MIN_KEY_PX && bounds.height() >= MIN_KEY_PX) {
                        keys += TreeKey(label, node.viewIdResourceName, bounds.toModel())
                    }
                }
                for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
            }
            NodeCompat.recycle(node)
        }
        stack.forEach(NodeCompat::recycle)
        return keys
    }

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val clickable =
            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        if (!clickable) return null
        return (node.contentDescription ?: node.text)?.toString()?.takeIf { it.isNotEmpty() }
    }
}
