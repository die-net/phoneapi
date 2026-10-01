package net.die.phoneapi.input

import android.view.KeyEvent
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.InputBackend
import net.die.phoneapi.model.TimedPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BackendSelectionTest {
    @Test
    fun `auto prefers inject`() {
        val backends = TouchBackends(a11y = touch("a11y", true), inject = touch("inject", true))
        assertEquals("inject", backends.select(InputBackend.AUTO).name)
    }

    @Test
    fun `auto falls back to a11y`() {
        val backends = TouchBackends(a11y = touch("a11y", true), inject = touch("inject", false))
        assertEquals("a11y", backends.select(InputBackend.AUTO).name)
        assertEquals("a11y", backends.select(InputBackend.A11Y).name)
    }

    @Test
    fun `down touch backends fail`() {
        val backends = TouchBackends(a11y = touch("a11y", false), inject = touch("inject", false))
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { backends.select(InputBackend.INJECT) }.error,
        )
        assertEquals(
            "accessibility_unavailable",
            assertThrows(ApiException::class.java) { backends.select(InputBackend.A11Y) }.error,
        )
    }

    @Test
    fun `home needs the helper`() {
        val keys = KeyBackends(ime = key("ime", true), helper = key("inject", true))
        assertEquals("inject", keys.forKey(KeyEvent.KEYCODE_HOME).name)
        val down = KeyBackends(ime = key("ime", true), helper = key("inject", false))
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { down.forKey(KeyEvent.KEYCODE_HOME) }.error,
        )
    }

    @Test
    fun `editing keys pick a backend`() {
        val both = KeyBackends(ime = key("ime", true), helper = key("inject", true))
        assertEquals("ime", both.forKey(KeyEvent.KEYCODE_A).name)
        val helperOnly = KeyBackends(ime = key("ime", false), helper = key("inject", true))
        assertEquals("inject", helperOnly.forKey(KeyEvent.KEYCODE_DEL).name)
        val neither = KeyBackends(ime = key("ime", false), helper = key("inject", false))
        assertEquals(
            "no_editor",
            assertThrows(ApiException::class.java) { neither.forKey(KeyEvent.KEYCODE_ENTER) }.error,
        )
    }

    private fun touch(name: String, available: Boolean) = FakeTouch(name, available)

    private fun key(name: String, available: Boolean) = FakeKey(name, available)

    private class FakeTouch(override val name: String, override val isAvailable: Boolean) :
        TouchBackend {
        override suspend fun perform(pointers: List<List<TimedPoint>>) = true
    }

    private class FakeKey(override val name: String, override val isAvailable: Boolean) :
        KeyBackend {
        override suspend fun send(event: KeyEvent) = true
    }
}
