package net.die.phoneapi.tree

import kotlinx.coroutines.flow.StateFlow
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.UiService
import net.die.phoneapi.helper.tree.ActionNames
import net.die.phoneapi.input.TouchInput
import net.die.phoneapi.model.ActionMode
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.UiSnapshot

/** [UiService] that asks the helper for the tree and injects real clicks itself. */
class HelperUiService(
    private val prepare: suspend (Boolean, Boolean) -> Boolean,
    private val tree: TreeSession,
    private val touch: TouchInput,
    private val seq: StateFlow<Long>,
) : UiService {
    override suspend fun snapshot(options: SnapshotOptions): UiSnapshot {
        prepare(options.autoWake, true)
        return tree.snapshot(options)
    }

    override suspend fun find(request: FindRequest): FindResult {
        prepare(true, true)
        return tree.find(request)
    }

    override suspend fun act(ref: String, request: NodeActionRequest): ActionResult {
        val woke = prepare(request.autoWake, false)
        val action = request.action
        val click = action.equals(ActionNames.CLICK, ignoreCase = true)
        val longClick = action.equals(ActionNames.LONG_CLICK, ignoreCase = true)
        val result =
            if (request.mode == ActionMode.REAL && (click || longClick)) {
                val target = tree.touchTarget(ref, request.force)
                val outcome = if (click) touch.tap(target) else touch.longPress(target)
                ActionResult(
                    ok = outcome.ok,
                    backend = outcome.backend,
                    points = outcome.points,
                    message = if (outcome.ok) null else "The system cancelled the gesture",
                )
            } else {
                tree.act(ref, action, request.text)
            }
        tree.invalidate()
        return result.copy(woke = woke, seq = seq.value)
    }
}
