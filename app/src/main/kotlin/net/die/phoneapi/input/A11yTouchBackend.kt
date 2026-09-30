package net.die.phoneapi.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.TimedPoint

/**
 * Touches through [AccessibilityService.dispatchGesture]. Since Android 12 the resulting events
 * carry `FLAG_IS_ACCESSIBILITY_EVENT`, so this is the fallback when the helper isn't running.
 */
class A11yTouchBackend(private val service: StateFlow<AccessibilityService?>) : TouchBackend {
    override val name = "a11y"

    override val isAvailable: Boolean
        get() = service.value != null

    // A new gesture cancels the one in flight, so gestures run one at a time.
    private val mutex = Mutex()

    override suspend fun perform(pointers: List<List<TimedPoint>>): Boolean {
        val svc = service.value ?: throw ApiException.a11yUnavailable()
        if (pointers.size > GestureDescription.getMaxStrokeCount()) {
            throw ApiException.badRequest(
                "At most ${GestureDescription.getMaxStrokeCount()} strokes per gesture"
            )
        }
        val chunks =
            try {
                GesturePlanner.plan(
                    pointers,
                    maxChunkMs = GestureDescription.getMaxGestureDuration(),
                )
            } catch (e: IllegalArgumentException) {
                throw ApiException.badRequest(e.message ?: "Invalid gesture", e)
            }
        return mutex.withLock {
            val strokes = HashMap<Int, StrokeDescription>()
            chunks.all { chunk ->
                val builder = GestureDescription.Builder()
                for (part in chunk.parts) {
                    val path = pathOf(part.points)
                    val start = part.points.first().tMs - chunk.startMs
                    val duration =
                        (part.points.last().tMs - part.points.first().tMs).coerceAtLeast(1)
                    val previous = strokes[part.pointer]
                    val stroke =
                        if (part.continued && previous != null) {
                            previous.continueStroke(path, start, duration, part.willContinue)
                        } else {
                            StrokeDescription(path, start, duration, part.willContinue)
                        }
                    strokes[part.pointer] = stroke
                    builder.addStroke(stroke)
                }
                dispatch(svc, builder.build())
            }
        }
    }

    private fun pathOf(points: List<TimedPoint>): Path {
        val path = Path()
        val first = points.first()
        path.moveTo(first.x.coerceAtLeast(0f), first.y.coerceAtLeast(0f))
        for (i in 1 until points.size) {
            val p = points[i]
            path.lineTo(p.x.coerceAtLeast(0f), p.y.coerceAtLeast(0f))
        }
        return path
    }

    private suspend fun dispatch(svc: AccessibilityService, gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            if (!svc.dispatchGesture(gesture, callback, null) && cont.isActive) cont.resume(false)
        }
}
