package net.die.phoneapi.input

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.toModel

/** Reads the on-screen keyboard's keys from the IME window's accessibility tree. */
internal object ImeKeyboard {
    private const val MAX_NODES = 600
    private const val MIN_KEY_PX = 16

    /** The IME's tappable keys, or an empty list if no IME window is showing. Blocking IPC. */
    fun scan(service: AccessibilityService): List<ImeKey> {
        val windows = service.windows
        try {
            val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val root = ime?.root ?: return emptyList()
            return collect(root)
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun collect(root: AccessibilityNodeInfo): List<ImeKey> {
        val keys = ArrayList<ImeKey>()
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
                        keys += ImeKey(label, node.viewIdResourceName, bounds.toModel())
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
