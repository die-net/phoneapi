package net.die.phoneapi.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.TimedPoint

/**
 * Touches through the helper's `InputManager.injectInputEvent`, stamped with the real touchscreen
 * so they are not accessibility gestures.
 */
class InjectTouchBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) : TouchBackend {
    override val name = "inject"

    override val isAvailable: Boolean
        get() = helper.isRunning

    private val gestures = Mutex()
    private val screenLock = Mutex()
    private var screen: Touchscreen? = null

    override suspend fun perform(pointers: List<List<TimedPoint>>): Boolean = gestures.withLock {
        val proxy = helper.require()
        val device = touchscreen(proxy)
        val samples =
            try {
                touchTimeline(pointers)
            } catch (e: IllegalArgumentException) {
                throw ApiException.badRequest(e.message ?: "Invalid gesture", e)
            }
        val events = motionEvents(samples, device, SystemClock.uptimeMillis())
        try {
            withContext(io) {
                var ok = true
                for (event in events) {
                    val wait = event.eventTime - SystemClock.uptimeMillis()
                    if (wait > 0) delay(wait)
                    ok = proxy.injectMotionEvent(event, WAIT_FOR_FINISH) && ok
                }
                ok
            }
        } finally {
            events.forEach { it.recycle() }
        }
    }

    private suspend fun touchscreen(proxy: IHelper): Touchscreen {
        screen?.let {
            return it
        }
        return screenLock.withLock {
            screen?.let {
                return it
            }
            val parsed = withContext(io) { parseTouchscreen(proxy.touchscreenInfo()) }
            screen = parsed
            parsed
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}

@Serializable
internal data class Touchscreen(
    val deviceId: Int = 0,
    val source: Int = InputDevice.SOURCE_TOUCHSCREEN,
    val pressure: AxisRange = AxisRange(0f, 1f),
    val touchMajor: AxisRange = AxisRange(0f, 1f),
    val touchMinor: AxisRange = AxisRange(0f, 1f),
    val orientation: AxisRange = AxisRange(0f, 0f),
    val size: AxisRange = AxisRange(0f, 1f),
)

@Serializable internal data class AxisRange(val min: Float = 0f, val max: Float = 1f)

internal fun parseTouchscreen(json: String): Touchscreen {
    if (json.isBlank() || json == "null") return Touchscreen()
    return try {
        ApiJson.decodeFromString(serializer<Touchscreen>(), json)
    } catch (_: SerializationException) {
        Touchscreen()
    }
}

private fun motionEvents(
    samples: List<TouchSample>,
    screen: Touchscreen,
    start: Long,
): List<MotionEvent> {
    val active = LinkedHashMap<Int, TouchSample>()
    var downTime = start
    val events = ArrayList<MotionEvent>(samples.size)
    for (sample in samples) {
        val eventTime = start + sample.tMs
        when (sample.phase) {
            TouchPhase.DOWN -> {
                if (active.isEmpty()) downTime = eventTime
                active[sample.pointer] = sample
                val action =
                    if (active.size == 1) {
                        MotionEvent.ACTION_DOWN
                    } else {
                        pointerAction(MotionEvent.ACTION_POINTER_DOWN, active, sample.pointer)
                    }
                events += obtain(downTime, eventTime, action, active.values.toList(), screen)
            }
            TouchPhase.MOVE -> {
                active[sample.pointer] = sample
                events +=
                    obtain(
                        downTime,
                        eventTime,
                        MotionEvent.ACTION_MOVE,
                        active.values.toList(),
                        screen,
                    )
            }
            TouchPhase.UP -> {
                active[sample.pointer] = sample
                val action =
                    if (active.size == 1) {
                        MotionEvent.ACTION_UP
                    } else {
                        pointerAction(MotionEvent.ACTION_POINTER_UP, active, sample.pointer)
                    }
                events += obtain(downTime, eventTime, action, active.values.toList(), screen)
                active.remove(sample.pointer)
            }
        }
    }
    return events
}

private fun pointerAction(base: Int, active: Map<Int, TouchSample>, pointer: Int): Int {
    val index = active.keys.indexOf(pointer).coerceAtLeast(0)
    return base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
}

@Suppress("DEPRECATION")
private fun obtain(
    downTime: Long,
    eventTime: Long,
    action: Int,
    pointers: List<TouchSample>,
    screen: Touchscreen,
): MotionEvent {
    val properties =
        Array(pointers.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = pointers[index].pointer
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
    val coords =
        Array(pointers.size) { index ->
            MotionEvent.PointerCoords().apply {
                val sample = pointers[index]
                x = sample.x
                y = sample.y
                pressure = along(screen.pressure, PRESSURE_FRACTION)
                size = along(screen.size, SIZE_FRACTION)
                touchMajor = along(screen.touchMajor, SIZE_FRACTION)
                touchMinor = along(screen.touchMinor, SIZE_FRACTION)
                orientation = along(screen.orientation, CENTER_FRACTION)
            }
        }
    // A device's source mask can include bits the input verifier does not know. On API 36 an
    // unknown source aborts system_server, so a finger is always a plain touchscreen event.
    val source =
        screen.source.takeIf { it == InputDevice.SOURCE_TOUCHSCREEN }
            ?: InputDevice.SOURCE_TOUCHSCREEN
    return MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        pointers.size,
        properties,
        coords,
        0,
        0,
        1f,
        1f,
        screen.deviceId,
        0,
        source,
        0,
    )
}

/**
 * A point [fraction] of the way from min to max, so a finger is not the exact center of the range.
 */
private fun along(range: AxisRange, fraction: Float): Float {
    if (range.max <= range.min) return range.min
    return range.min + (range.max - range.min) * fraction
}

private const val PRESSURE_FRACTION = 0.55f
private const val SIZE_FRACTION = 0.08f
private const val CENTER_FRACTION = 0.5f
