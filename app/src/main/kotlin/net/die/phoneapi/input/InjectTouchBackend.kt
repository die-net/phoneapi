package net.die.phoneapi.input

import android.os.RemoteException
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.helper.TouchscreenInfo
import net.die.phoneapi.helperclient.HelperConnection
import net.die.phoneapi.model.PointerFrame
import net.die.phoneapi.model.TimedPoint

/**
 * Touches through the helper's `InputManager.injectInputEvent`, stamped with the real touchscreen
 * so they are not accessibility gestures.
 */
class InjectTouchBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) {
    val name = "inject"

    /** The touchscreen axes. CDP maps the same finger fractions through them. */
    internal suspend fun touchscreen(): TouchscreenInfo {
        val proxy = helper.require()
        return try {
            touchscreen(proxy)
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private val gestures = Mutex()
    private val screenLock = Mutex()

    /** Dropped when [helper] hands out a different binder proxy. */
    @Volatile private var screenProxy: IHelper? = null
    @Volatile private var screen: TouchscreenInfo? = null

    suspend fun perform(pointers: List<List<TimedPoint>>): Boolean =
        shapedPerform(pointers, emptyList())

    internal suspend fun shapedPerform(
        pointers: List<List<TimedPoint>>,
        fingers: List<List<FingerShape>>,
    ): Boolean = gestures.withLock {
        val proxy = helper.require()
        val device =
            try {
                touchscreen(proxy)
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            }
        val samples =
            try {
                touchTimeline(pointers, fingers)
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
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        } finally {
            events.forEach { it.recycle() }
        }
    }

    /**
     * Injects one gesture as frames arrive. The [gestures] lock is held until the last finger lifts
     * or the gesture is cancelled. Closing [frames], or cancelling this coroutine, injects
     * `ACTION_CANCEL` while a contact is still down so the touch is released.
     */
    suspend fun playLive(
        first: PointerFrame,
        frames: ReceiveChannel<PointerFrame>,
        clamp: (PointerFrame) -> PointerFrame = { it },
    ) {
        gestures.withLock {
            val proxy = helper.require()
            val device =
                try {
                    touchscreen(proxy)
                } catch (e: RemoteException) {
                    throw ApiException.helperDropped(e)
                }
            val live = LiveTouch(proxy, device)
            try {
                playPointerGesture(first, frames, clamp) { event -> live.inject(event) }
            } finally {
                withContext(NonCancellable) { live.release() }
            }
        }
    }

    /**
     * One finger-down span. Active contacts are recorded before the binder call so a disconnect
     * during injection still has something to cancel. The binder call itself is not cancellable: a
     * down that the system already accepted has to be remembered, or the finger would stick.
     */
    private inner class LiveTouch(private val proxy: IHelper, private val screen: TouchscreenInfo) {
        private var downTime = 0L
        private val active = LinkedHashMap<Int, PointerPoint>()

        suspend fun inject(event: PointerEvent) {
            if (event.points.isEmpty()) return
            val now = SystemClock.uptimeMillis()
            if (active.isEmpty()) downTime = now
            val eventTime =
                if (now <= downTime) downTime else (downTime + event.tMs).coerceIn(downTime, now)
            val samples =
                event.points.map { point ->
                    TouchSample(point.id, point.x, point.y, event.tMs, TouchPhase.MOVE)
                }
            val motion = obtain(downTime, eventTime, motionAction(event), samples, screen)
            // A down or move is recorded first, so a disconnect mid-call can still cancel that
            // finger. A lift is recorded only after the call, so a failed up does not forget it.
            val lifts =
                event.phase == PointerPhase.POINTER_UP ||
                    event.phase == PointerPhase.UP ||
                    event.phase == PointerPhase.CANCEL
            if (!lifts) remember(event)
            try {
                withContext(NonCancellable) {
                    withContext(io) { proxy.injectMotionEvent(motion, WAIT_FOR_FINISH) }
                }
                if (lifts) remember(event)
            } catch (e: RemoteException) {
                throw ApiException.helperDropped(e)
            } finally {
                motion.recycle()
            }
        }

        suspend fun release() {
            if (active.isEmpty()) return
            val points = active.values.toList()
            val event =
                PointerEvent(
                    PointerPhase.CANCEL,
                    id = -1,
                    points = points,
                    tMs = points.maxOf { it.tMs },
                )
            inject(event)
        }

        private fun remember(event: PointerEvent) {
            when (event.phase) {
                PointerPhase.DOWN,
                PointerPhase.POINTER_DOWN,
                PointerPhase.MOVE -> {
                    active.clear()
                    event.points.forEach { active[it.id] = it }
                }
                PointerPhase.POINTER_UP -> active.remove(event.id)
                PointerPhase.UP,
                PointerPhase.CANCEL -> active.clear()
            }
        }

        private fun motionAction(event: PointerEvent): Int =
            when (event.phase) {
                PointerPhase.DOWN -> MotionEvent.ACTION_DOWN
                PointerPhase.POINTER_DOWN -> pointerIndex(MotionEvent.ACTION_POINTER_DOWN, event)
                PointerPhase.MOVE -> MotionEvent.ACTION_MOVE
                PointerPhase.POINTER_UP -> pointerIndex(MotionEvent.ACTION_POINTER_UP, event)
                PointerPhase.UP -> MotionEvent.ACTION_UP
                PointerPhase.CANCEL -> MotionEvent.ACTION_CANCEL
            }

        private fun pointerIndex(base: Int, event: PointerEvent): Int {
            val index = event.points.indexOfFirst { it.id == event.id }.coerceAtLeast(0)
            return base or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
    }

    private suspend fun touchscreen(proxy: IHelper): TouchscreenInfo {
        if (screenProxy === proxy) {
            screen?.let {
                return it
            }
        }
        return screenLock.withLock {
            if (screenProxy === proxy) {
                screen?.let {
                    return it
                }
            }
            val parsed = withContext(io) { proxy.touchscreenInfo() }
            screenProxy = proxy
            screen = parsed
            parsed
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}

private fun motionEvents(
    samples: List<TouchSample>,
    screen: TouchscreenInfo,
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
    screen: TouchscreenInfo,
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
                val contact = FingerContact.from(screen, sample.shape)
                x = sample.x
                y = sample.y
                pressure = contact.pressure
                size = contact.size
                touchMajor = contact.touchMajor
                touchMinor = contact.touchMinor
                orientation = contact.orientation
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
