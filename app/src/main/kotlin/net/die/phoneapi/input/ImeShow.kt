package net.die.phoneapi.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.a11y.NodeCompat
import net.die.phoneapi.a11y.PhoneAccessibilityService
import net.die.phoneapi.a11y.SelectorMatcher
import net.die.phoneapi.a11y.SnapshotEngine
import net.die.phoneapi.a11y.require
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.NodeSelector

/**
 * Focuses an editable node and clicks it so the IME appears. Callers run this inside the input
 * action wrapper, off the main thread.
 */
internal class ImeShow(
    private val a11y: StateFlow<PhoneAccessibilityService?>,
    private val state: DeviceStateTracker,
    private val snapshots: SnapshotEngine,
) {
    suspend fun show(request: ImeShowRequest): ActionResult {
        val service = a11y.require()
        val node = resolve(service, request.selector)
        try {
            if (!node.isEditable) throw ApiException.badRequest("The node is not editable")
            // A hide interrupted mid-fallback can leave SHOW_MODE_HIDDEN, which blocks the
            // keyboard.
            service.softKeyboardController.showMode = AccessibilityService.SHOW_MODE_AUTO
            if (alreadyVisible(service, node)) {
                return ActionResult(ok = true, message = "The keyboard is already visible")
            }
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val shown =
                withTimeoutOrNull(SHOW_WAIT_MS) { state.state.first { it.ime.visible } } != null
            return ActionResult(
                ok = shown,
                backend = "a11y",
                message = if (shown) null else "The keyboard did not appear",
            )
        } finally {
            NodeCompat.recycle(node)
        }
    }

    private suspend fun resolve(
        service: AccessibilityService,
        selector: NodeSelector?,
    ): AccessibilityNodeInfo {
        if (selector == null || !selector.targetsNode()) {
            return service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: throw ApiException.badRequest("No editable field is focused")
        }
        return snapshots.resolve(selector)
    }

    private fun alreadyVisible(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
    ): Boolean {
        if (!state.ime.visible) return false
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (focused === node) return true
        return try {
            focused == node
        } finally {
            NodeCompat.recycle(focused)
        }
    }

    private fun NodeSelector.targetsNode(): Boolean =
        ref != null || index != null || !SelectorMatcher(this).isEmpty

    private companion object {
        const val SHOW_WAIT_MS = 2_000L
    }
}
