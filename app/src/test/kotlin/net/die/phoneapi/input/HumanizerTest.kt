package net.die.phoneapi.input

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HumanizerTest {
    @Test
    fun `fallback finger matches the injected contact`() {
        val contact = FingerContact.fallback
        assertEquals(0.55f, contact.pressure, 0.0001f)
        assertEquals(0.08f, contact.size, 0.0001f)
        assertEquals(0.08f, contact.touchMajor, 0.0001f)
        assertEquals(0.08f, contact.touchMinor, 0.0001f)
        assertEquals(0f, contact.orientation, 0.0001f)
        val cdp = contact.toCdp(2.5f)
        assertEquals(0.016f, cdp.radiusX, 0.0001f)
        assertEquals(0.016f, cdp.radiusY, 0.0001f)
        assertEquals(0.55f, cdp.force, 0.0001f)
        assertEquals(0f, cdp.rotationAngle, 0.0001f)
    }

    private val target = Rect(100, 200, 300, 300)

    @Test
    fun `tap point and hold ranges`() {
        repeat(500) { seed ->
            val path = Humanizer(Random(seed)).tap(target)
            val down = path.first()
            assertTrue(down.x in 140f..260f, "x=${down.x}")
            assertTrue(down.y in 220f..280f, "y=${down.y}")
            assertEquals(0L, down.tMs)
            assertTrue(path.last().tMs in 50L..120L, "hold=${path.last().tMs}")
            assertIncreasing(path)
        }
    }

    @Test
    fun `tap has 1-3 tiny micro-moves`() {
        repeat(200) { seed ->
            val path = Humanizer(Random(seed)).tap(target)
            val moves = path.size - 2
            assertTrue(moves in 1..3, "moves=$moves")
            path.zipWithNext().forEach { (a, b) ->
                assertTrue(abs(a.x - b.x) <= 1f && abs(a.y - b.y) <= 1f)
            }
        }
    }

    @Test
    fun `tap honors requested hold`() {
        val path = Humanizer(Random(1)).tap(target, holdMs = 1_000)
        assertTrue(path.last().tMs in 950L..1_050L)
    }

    @Test
    fun `tap is spread around target`() {
        val xs = (0 until 200).map { Humanizer(Random(it)).tap(target).first().x }.toSet()
        assertTrue(xs.size > 100)
    }

    @Test
    fun `plain tap is centered`() {
        val path = Humanizer(Random(3)).tap(target, humanize = false)
        assertEquals(listOf(TimedPoint(200f, 250f, 0), TimedPoint(200f, 250f, 60)), path)
    }

    @Test
    fun `double tap gap and spread`() {
        repeat(200) { seed ->
            val strokes = Humanizer(Random(seed)).taps(target, count = 2)
            assertEquals(2, strokes.size)
            val gap = strokes[1].first().tMs - strokes[0].last().tMs
            assertTrue(gap in 90L..160L, "gap=$gap")
            val d = distance(strokes[0].first(), strokes[1].first())
            assertTrue(d < 15f, "second tap drifted $d px")
        }
    }

    @Test
    fun `swipe hits endpoints in order`() {
        val from = Point(500f, 1800f)
        val to = Point(500f, 600f)
        repeat(100) { seed ->
            val path = Humanizer(Random(seed)).swipe(from, to, SwipeSpec(durationMs = 300))
            assertEquals(from.x, path.first().x)
            assertEquals(from.y, path.first().y)
            assertEquals(to.x, path.last().x, 0.01f)
            assertEquals(to.y, path.last().y, 0.01f)
            assertIncreasing(path)
            assertTrue(path.last().tMs in 270L..330L)
        }
    }

    @Test
    fun `swipe uses the sample rate`() {
        val path =
            Humanizer(Random(1))
                .swipe(
                    Point(0f, 0f),
                    Point(0f, 1000f),
                    SwipeSpec(durationMs = 500, humanize = false, sampleRateHz = 120f),
                )
        assertEquals(61, path.size)
    }

    @Test
    fun `humanized swipe bows slightly`() {
        repeat(100) { seed ->
            val path =
                Humanizer(Random(seed)).swipe(Point(500f, 2000f), Point(500f, 1000f), SwipeSpec())
            val maxDeviation = path.maxOf { abs(it.x - 500f) }
            assertTrue(maxDeviation in 15f..65f, "deviation=$maxDeviation")
        }
    }

    @Test
    fun `plain swipe is straight`() {
        val path =
            Humanizer(Random(1))
                .swipe(
                    Point(0f, 0f),
                    Point(0f, 600f),
                    SwipeSpec(durationMs = 300, humanize = false),
                )
        assertTrue(path.all { it.x == 0f })
        val speeds = path.zipWithNext().map { (a, b) -> (b.y - a.y) / (b.tMs - a.tMs) }
        assertTrue(speeds.max() - speeds.min() < 0.2f, "speeds=$speeds")
    }

    @Test
    fun `fling releases at speed`() {
        val from = Point(500f, 2000f)
        val to = Point(500f, 800f)
        val fling = Humanizer(Random(7)).swipe(from, to, SwipeSpec(fling = true))
        val settle = Humanizer(Random(7)).swipe(from, to, SwipeSpec(fling = false))
        assertTrue(endSpeed(fling) > 3f, "fling end speed ${endSpeed(fling)}")
        assertTrue(endSpeed(settle) < 0.5f, "settle end speed ${endSpeed(settle)}")
        val settleDwell = settle.last().tMs - settle[settle.lastIndex - 1].tMs
        assertTrue(settleDwell in 40L..90L)
    }

    @Test
    fun `settled swipe eases in-out`() {
        val path =
            Humanizer(Random(9))
                .swipe(Point(0f, 0f), Point(0f, 1200f), SwipeSpec(durationMs = 400, fling = false))
        val speeds = path.dropLast(1).zipWithNext().map { (a, b) -> speed(a, b) }
        val peak = speeds.indexOf(speeds.max())
        assertTrue(speeds.first() < speeds.max() / 3)
        assertTrue(peak in speeds.size / 4..speeds.size * 3 / 4)
    }

    @Test
    fun `same seed gives the same path`() {
        val a = Humanizer(Random(42)).swipe(Point(1f, 2f), Point(300f, 900f))
        val b = Humanizer(Random(42)).swipe(Point(1f, 2f), Point(300f, 900f))
        assertEquals(a, b)
    }

    @Test
    fun `long press outlasts timeout`() {
        val path = Humanizer(Random(5)).longPress(target, longPressTimeoutMs = 400)
        assertTrue(path.last().tMs in 500L..800L)
    }

    private fun endSpeed(path: List<TimedPoint>): Float {
        val window = path.takeLast(3)
        return speed(window.first(), window.last())
    }

    private fun speed(a: TimedPoint, b: TimedPoint) = distance(a, b) / (b.tMs - a.tMs)

    private fun distance(a: TimedPoint, b: TimedPoint) = hypot(b.x - a.x, b.y - a.y)

    private fun assertIncreasing(path: List<TimedPoint>) {
        path.zipWithNext().forEach { (a, b) -> assertTrue(b.tMs > a.tMs, "times $path") }
    }
}
