package net.die.phoneapi.browser

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.Rect

/**
 * The WebView that is actually showing the page. Chrome's window includes the toolbar, so quads
 * have to be added to this view's origin, not the window's.
 */
internal object ContentFrame {
    private const val MIN_EDGE = 20

    fun find(windows: List<AccessibilityWindowInfo>, packageName: String): Rect {
        val wanted = packageName.takeIf { it.isNotEmpty() }
        try {
            var best: AndroidRect? = null
            for (window in windows) {
                val root = ownedRoot(window, wanted) ?: continue
                try {
                    best = larger(best, search(root))
                } finally {
                    NodeCompat.recycle(root)
                }
            }
            val found =
                best
                    ?: throw ApiException(
                        409,
                        "content_unavailable",
                        "The page is not on screen",
                    )
            return Rect(found.left, found.top, found.right, found.bottom)
        } finally {
            NodeCompat.recycleAll(windows)
        }
    }

    private fun ownedRoot(
        window: AccessibilityWindowInfo,
        packageName: String?,
    ): AccessibilityNodeInfo? {
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
        val root = window.root ?: return null
        if (packageName != null && root.packageName?.toString() != packageName) {
            NodeCompat.recycle(root)
            return null
        }
        return root
    }

    private fun search(node: AccessibilityNodeInfo): AndroidRect? {
        var best = boundsOf(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                best = larger(best, search(child))
            } finally {
                NodeCompat.recycle(child)
            }
        }
        return best
    }

    private fun boundsOf(node: AccessibilityNodeInfo): AndroidRect? {
        if (!isContent(node) || !node.isVisibleToUser) return null
        val rect = AndroidRect().also(node::getBoundsInScreen)
        if (rect.width() < MIN_EDGE || rect.height() < MIN_EDGE) return null
        return rect
    }

    private fun isContent(node: AccessibilityNodeInfo): Boolean {
        val name = node.className?.toString() ?: return false
        return name.endsWith("WebView") ||
            name.endsWith("CompositorViewHolder") ||
            name.endsWith("ContentView")
    }

    private fun larger(current: AndroidRect?, next: AndroidRect?): AndroidRect? {
        if (next == null) return current
        if (current == null || area(next) > area(current)) return next
        return current
    }

    private fun area(rect: AndroidRect): Int = rect.width() * rect.height()
}
