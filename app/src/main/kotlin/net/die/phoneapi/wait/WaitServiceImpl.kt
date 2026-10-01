package net.die.phoneapi.wait

import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.a11y.PhoneAccessibilityService
import net.die.phoneapi.a11y.SnapshotEngine
import net.die.phoneapi.a11y.UiChangeTracker
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.SnapshotOptions
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

/**
 * The single condition engine behind `POST /v1/wait`. Conditions are re-evaluated when something
 * actually changes — accessibility content and window changes, device state, and anything on the
 * event bus — rather than on a polling interval. Conditions that depend only on time passing (an
 * idle UI) ask to be looked at again at a deadline instead.
 *
 * Browser conditions open a DevTools session for the wait and wake the same loop when Chrome
 * reports a page, network, or target event.
 */
class WaitServiceImpl(
    private val prepare: suspend (Boolean, Boolean) -> Boolean,
    private val uiTracker: UiChangeTracker,
    private val state: DeviceStateTracker,
    private val bus: EventBus,
    private val snapshots: SnapshotEngine,
    private val a11y: StateFlow<PhoneAccessibilityService?>,
    private val io: CoroutineDispatcher,
    private val browser: BrowserService,
    private val helper: HelperConnection,
) : WaitService {
    private data class Outcome(
        val matched: Boolean,
        val matchedAll: List<Int>,
        val matchedAny: List<Int>,
        val recheckAtMs: Long?,
    )

    override suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult {
        requireWaitAccess(request, scopes)
        validate(request)
        // Waiting never wakes the device on its own, but it does keep the screen from going dark
        // underneath a long wait.
        prepare(false, true)
        val browserWatch =
            BrowserWatch(browser, io, helper).takeIf {
                (request.all + request.any).any(::isBrowserCondition)
            }
        val watchers =
            ConditionWatchers(
                snapshots = snapshots,
                state = state,
                a11y = a11y,
                io = io,
                lastChangeMs = { uiTracker.lastChangeMs },
                browser = browserWatch,
            )
        val all = request.all.map(watchers::watcher)
        val any = request.any.map(watchers::watcher)
        val started = SystemClock.uptimeMillis()
        val deadline = started + request.timeoutMs
        return coroutineScope {
            val changes = Channel<Unit>(Channel.CONFLATED)
            try {
                browserWatch?.open(request.all + request.any, request.timeoutMs)
                val pump: Job = launch {
                    changeSources(browserWatch).collect { changes.trySend(Unit) }
                }
                try {
                    val outcome = await(all, any, deadline, changes)
                    if (outcome.matched) settle(request.settleMs, deadline, changes)
                    result(request, outcome, SystemClock.uptimeMillis() - started)
                } finally {
                    pump.cancel()
                }
            } finally {
                browserWatch?.close()
            }
        }
    }

    private suspend fun await(
        all: List<ConditionWatcher>,
        any: List<ConditionWatcher>,
        deadline: Long,
        changes: ReceiveChannel<Unit>,
    ): Outcome {
        while (true) {
            val outcome = evaluate(all, any)
            val now = SystemClock.uptimeMillis()
            if (outcome.matched || now >= deadline) return outcome
            val until = minOf(deadline, outcome.recheckAtMs ?: Long.MAX_VALUE)
            val changed = withTimeoutOrNull((until - now).coerceAtLeast(1)) { changes.receive() }
            if (changed != null) {
                // Coalesce the burst of events a single screen transition produces, so the tree is
                // only searched once per settled change.
                delay(DEBOUNCE_MS)
                changes.tryReceive()
            }
        }
    }

    private suspend fun evaluate(
        all: List<ConditionWatcher>,
        any: List<ConditionWatcher>,
    ): Outcome {
        val matchedAll = ArrayList<Int>(all.size)
        val matchedAny = ArrayList<Int>(any.size)
        var recheckAtMs: Long? = null
        all.forEachIndexed { i, watcher ->
            val check = watcher.check()
            if (check.satisfied) matchedAll += i
            recheckAtMs = earliest(recheckAtMs, check.recheckAtMs)
        }
        any.forEachIndexed { i, watcher ->
            val check = watcher.check()
            if (check.satisfied) matchedAny += i
            recheckAtMs = earliest(recheckAtMs, check.recheckAtMs)
        }
        val matched = matchedAll.size == all.size && (any.isEmpty() || matchedAny.isNotEmpty())
        return Outcome(matched, matchedAll, matchedAny, recheckAtMs)
    }

    /** Holds the result back until the UI has been quiet for [settleMs], or time runs out. */
    private suspend fun settle(settleMs: Long, deadline: Long, changes: ReceiveChannel<Unit>) {
        if (settleMs <= 0) return
        while (true) {
            val now = SystemClock.uptimeMillis()
            val quietAt = uiTracker.lastChangeMs + settleMs
            if (now >= quietAt || now >= deadline) return
            withTimeoutOrNull((minOf(quietAt, deadline) - now).coerceAtLeast(1)) {
                changes.receive()
            }
        }
    }

    private fun changeSources(browser: BrowserWatch?): Flow<Any> {
        val device =
            merge(
                uiTracker.seq.drop(1),
                uiTracker.windowsVersion.drop(1),
                state.state.drop(1),
                bus.events,
            )
        val page = browser?.changes ?: return device
        return merge(device, page)
    }

    private suspend fun result(
        request: WaitRequest,
        outcome: Outcome,
        elapsedMs: Long,
    ): WaitResult =
        WaitResult(
            matched = outcome.matched,
            timedOut = !outcome.matched,
            elapsedMs = elapsedMs,
            matchedAll = outcome.matchedAll,
            matchedAny = outcome.matchedAny,
            snapshot =
                if (request.snapshot) {
                    snapshots.snapshot(SnapshotOptions(format = request.snapshotFormat))
                } else {
                    null
                },
            state = state.refresh(),
        )

    private fun validate(request: WaitRequest) {
        val count = request.all.size + request.any.size
        if (count == 0) throw ApiException.badRequest("Pass at least one condition in all or any")
        if (count > MAX_CONDITIONS) {
            throw ApiException.badRequest("At most $MAX_CONDITIONS conditions per wait")
        }
        if (request.timeoutMs !in 0..MAX_TIMEOUT_MS) {
            throw ApiException.badRequest("timeoutMs must be 0..$MAX_TIMEOUT_MS")
        }
        if (request.settleMs !in 0..MAX_SETTLE_MS) {
            throw ApiException.badRequest("settleMs must be 0..$MAX_SETTLE_MS")
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 40L
        const val MAX_CONDITIONS = 16
        const val MAX_TIMEOUT_MS = 300_000L
        const val MAX_SETTLE_MS = 30_000L
    }
}

private fun earliest(a: Long?, b: Long?): Long? =
    if (a == null || b == null) a ?: b else minOf(a, b)
