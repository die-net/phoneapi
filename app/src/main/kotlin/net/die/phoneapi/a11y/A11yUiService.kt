package net.die.phoneapi.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.UiService
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.UiSnapshot

/** [UiService] over the accessibility tree: snapshots and queries, plus actions on refs. */
class A11yUiService(
    private val prepare: suspend (Boolean, Boolean) -> Boolean,
    private val snapshots: SnapshotEngine,
    private val a11y: StateFlow<PhoneAccessibilityService?>,
    private val io: CoroutineDispatcher,
    private val nodes: NodeRegistry,
    private val targeting: NodeTargeting,
    private val touch: TouchInput,
    private val seq: StateFlow<Long>,
) : UiService {
    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        prepare(options.autoWake, true)
        return snapshots.snapshot(options)
    }

    override suspend fun find(request: FindRequest): FindResult {
        prepare(true, true)
        return snapshots.find(request)
    }

    override suspend fun act(ref: String, request: NodeActionRequest): ActionResult {
        val woke = prepare(request.autoWake, false)
        val service = a11y.require()
        val result =
            withContext(io) { nodes.withNode(ref) { node -> perform(service, node, request) } }
        snapshots.invalidate()
        return result.copy(woke = woke, seq = seq.value)
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
            val target = targeting.touchTarget(service, node, request.force)
            val outcome = if (click) touch.tap(target) else touch.longPress(target)
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
