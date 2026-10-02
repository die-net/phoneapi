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
    fun `auto uses inject`() {
        val backends = TouchBackends(inject = touch("inject", true))
        assertEquals("inject", backends.select(InputBackend.AUTO).name)
        assertEquals("inject", backends.select(InputBackend.INJECT).name)
    }

    @Test
    fun `down touch backends fail`() {
        val backends = TouchBackends(inject = touch("inject", false))
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { backends.select(InputBackend.INJECT) }.error,
        )
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { backends.select(InputBackend.AUTO) }.error,
        )
    }

    @Test
    fun `keys need the helper`() {
        val keys = KeyBackends(helper = key("inject", true))
        assertEquals("inject", keys.forKey(KeyEvent.KEYCODE_HOME).name)
        assertEquals("inject", keys.forKey(KeyEvent.KEYCODE_A).name)
        val down = KeyBackends(helper = key("inject", false))
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { down.forKey(KeyEvent.KEYCODE_HOME) }.error,
        )
        assertEquals(
            "helper_unavailable",
            assertThrows(ApiException::class.java) { down.forKey(KeyEvent.KEYCODE_ENTER) }.error,
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
