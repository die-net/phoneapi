package net.die.phoneapi.tabs

import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

/**
 * Waits on the tabs a caller may use. A `browser.*` condition's tab must be one [check] allows.
 * With [browser], one that names no tab gets the only page the caller can see, rather than
 * whichever page is open; without it, the wait service picks as before.
 */
internal class SessionWaits(
    private val base: WaitService,
    private val browser: BrowserService?,
    private val check: suspend (String) -> Unit,
) : WaitService {
    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        val all = request.all.map { scoped(it) }
        val any = request.any.map { scoped(it) }
        return base.wait(request.copy(all = all, any = any), scopes)
    }

    private suspend fun scoped(condition: WaitCondition): WaitCondition {
        val slot = targeted(condition) ?: return condition
        val target = slot.target
        if (target != null) {
            check(target)
            return condition
        }
        if (browser == null) return condition
        val pages = browser.targets().filter { it.type == "page" }
        val only =
            pages.singleOrNull()
                ?: throw ApiException.badRequest(
                    "Pass target: you can see ${pages.size} browser tabs, not one"
                )
        return slot.with(only.id)
    }
}

/** A condition's tab, and the condition with another tab in its place. */
private data class Targeted(val target: String?, val with: (String) -> WaitCondition)

/** The tab a condition is on. Null for conditions on no tab, such as browser.newTarget. */
private fun targeted(condition: WaitCondition): Targeted? =
    when (condition) {
        is WaitCondition.BrowserUrl -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserLifecycle ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserNetworkIdle ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserElement ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserRequest ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserDialog -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserLog -> Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserSettled ->
            Targeted(condition.target) { condition.copy(target = it) }
        is WaitCondition.BrowserNewTarget,
        is WaitCondition.Node,
        is WaitCondition.Window,
        is WaitCondition.Ime,
        is WaitCondition.Idle,
        is WaitCondition.Screen,
        is WaitCondition.Keyguard -> null
    }
