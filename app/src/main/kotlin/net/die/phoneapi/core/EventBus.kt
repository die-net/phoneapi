package net.die.phoneapi.core

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import net.die.phoneapi.model.Event

/** Process-wide fan-out of device events to WebSocket clients and the wait engine. */
class EventBus {
    private val seq = AtomicLong()
    private val flow =
        MutableSharedFlow<Event>(
            extraBufferCapacity = 256,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    val events: SharedFlow<Event> = flow.asSharedFlow()

    val lastSeq: Long
        get() = seq.get()

    fun emit(type: String, data: JsonObject = JsonObject(emptyMap())) {
        flow.tryEmit(next(type, data))
    }

    /** Same clock and sequence as [emit], without publishing on [events]. */
    fun next(type: String, data: JsonObject = JsonObject(emptyMap())): Event =
        Event(type, System.currentTimeMillis(), seq.incrementAndGet(), data)
}
