package net.die.phoneapi.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.DeviceStateSummary

/**
 * Waits until [predicate] holds for the live device state, or [timeoutMs] passes. Screen, keyguard
 * and IME changes arrive as broadcasts and accessibility events, so this re-reads the state on
 * every state change and every published event rather than polling.
 */
suspend fun awaitDeviceState(
    state: DeviceStateTracker,
    bus: EventBus,
    timeoutMs: Long,
    predicate: (DeviceStateSummary) -> Boolean,
): Boolean {
    if (predicate(state.current)) return true
    val changes: Flow<Any> = merge(state.state, bus.events)
    return withTimeoutOrNull(timeoutMs) { changes.first { predicate(state.current) } } != null
}
