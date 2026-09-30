package net.die.phoneapi.input

import kotlin.random.Random
import net.die.phoneapi.model.Point
import net.die.phoneapi.model.Rect
import net.die.phoneapi.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GesturePlannerTest {
    @Test
    fun `taps fit in one chunk`() {
        val strokes = Humanizer(Random(1)).taps(Rect(0, 0, 100, 100), count = 2)
        val chunks = GesturePlanner.plan(strokes)
        assertEquals(1, chunks.size)
        assertEquals(2, chunks[0].parts.size)
        assertTrue(chunks[0].parts.none { it.continued || it.willContinue })
    }

    @Test
    fun `steady swipe is one chunk`() {
        val path =
            Humanizer(Random(1))
                .swipe(Point(0f, 0f), Point(0f, 900f), SwipeSpec(humanize = false, fling = true))
        assertEquals(1, GesturePlanner.plan(listOf(path)).size)
    }

    @Test
    fun `eased swipe chunks join up`() {
        val path =
            Humanizer(Random(1))
                .swipe(Point(500f, 2000f), Point(500f, 800f), SwipeSpec(fling = false))
        val chunks = GesturePlanner.plan(listOf(path))
        assertTrue(chunks.size > 2, "chunks=${chunks.size}")
        chunks.forEachIndexed { i, chunk ->
            val part = chunk.parts.single()
            assertEquals(i > 0, part.continued)
            assertEquals(i < chunks.lastIndex, part.willContinue)
            assertTrue(chunk.endMs - chunk.startMs >= 1)
        }
        chunks.zipWithNext().forEach { (a, b) ->
            val end = a.parts.single().points.last()
            val start = b.parts.single().points.first()
            assertEquals(end.x, start.x)
            assertEquals(end.y, start.y)
        }
        assertEquals(path.first().tMs, chunks.first().startMs)
        assertEquals(path.last().tMs, chunks.last().endMs)
    }

    @Test
    fun `late pointer joins mid-gesture`() {
        val a = listOf(TimedPoint(0f, 0f, 0), TimedPoint(0f, 500f, 100), TimedPoint(0f, 520f, 400))
        val b = listOf(TimedPoint(100f, 0f, 200), TimedPoint(100f, 300f, 400))
        val chunks = GesturePlanner.plan(listOf(a, b))
        val withB = chunks.filter { c -> c.parts.any { it.pointer == 1 } }
        assertFalse(withB.first().parts.first { it.pointer == 1 }.continued)
        assertFalse(withB.last().parts.first { it.pointer == 1 }.willContinue)
    }

    @Test
    fun `single point held for 1 ms`() {
        val chunks = GesturePlanner.plan(listOf(listOf(TimedPoint(5f, 5f, 0))))
        assertEquals(1, chunks.single().endMs)
    }

    @Test
    fun `decreasing times are rejected`() {
        assertThrows<IllegalArgumentException> {
            GesturePlanner.plan(listOf(listOf(TimedPoint(0f, 0f, 10), TimedPoint(0f, 0f, 5))))
        }
    }

    @Test
    fun `long gesture split at max`() {
        val path = listOf(TimedPoint(0f, 0f, 0), TimedPoint(0f, 0f, 150_000))
        val chunks = GesturePlanner.plan(listOf(path))
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.endMs - it.startMs <= 60_000 })
    }
}
