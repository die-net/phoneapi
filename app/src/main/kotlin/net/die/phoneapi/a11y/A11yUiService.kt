package net.die.phoneapi.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.withContext
import net.die.phoneapi.AppGraph
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.UiService
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.UiSnapshot

/** [UiService] over the accessibility tree: snapshots and queries, plus actions on refs. */
class A11yUiService(private val graph: AppGraph) : UiService {
    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        graph.prepareForAction(options.autoWake, allowLocked = true)
        return graph.snapshots.snapshot(options)
    }

    override suspend fun find(request: FindRequest): FindResult {
        graph.prepareForAction(autoWake = true, allowLocked = true)
        return graph.snapshots.find(request)
    }

    override suspend fun act(ref: String, request: NodeActionRequest): ActionResult {
        val woke = graph.prepareForAction(request.autoWake)
        val service = graph.a11y.require()
        val result =
            withContext(graph.ioDispatcher) {
                graph.nodes.withNode(ref) { node -> perform(service, node, request) }
            }
        graph.snapshots.invalidate()
        return result.copy(woke = woke, seq = graph.uiTracker.seq.value)
    }

    private suspend fun perform(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        request: NodeActionRequest,
    ): ActionResult {
        val action = request.action
        val click = action.equals(ActionNames.CLICK, ignoreCase = true)
        val longClick = action.equals(ActionNames.LONG_CLICK, ignoreCase = true)
        if (request.mode == ActionMode.REAL && (click || longClick)) {
            val target = graph.targeting.touchTarget(service, node, request.force)
            val outcome = if (click) graph.touch.tap(target) else graph.touch.longPress(target)
            return ActionResult(
                ok = outcome.ok,
                backend = outcome.backend,
                points = outcome.points,
                message = if (outcome.ok) null else "The system cancelled the gesture",
            )
        }
        return semantic(node, action, request.text)
    }

    private fun semantic(node: AccessibilityNodeInfo, action: String, text: String?): ActionResult {
        if (
            action.equals(ActionNames.IME_ENTER, ignoreCase = true) &&
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R
        ) {
            throw ApiException(422, "unsupported", "imeEnter needs Android 11 or later")
        }
        val platform =
            ActionCatalog.forName(action)
                ?: ActionCatalog.custom(node, action)
                ?: throw ApiException.badRequest("Unknown action '$action'")
        val args =
            if (platform.id == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                val value = text ?: throw ApiException.badRequest("setText needs text")
                Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value,
                    )
                }
            } else {
                null
            }
        val ok = node.performAction(platform.id, args)
        return ActionResult(
            ok = ok,
            backend = "semantic",
            message = if (ok) null else "The node rejected '$action'",
        )
    }
}
