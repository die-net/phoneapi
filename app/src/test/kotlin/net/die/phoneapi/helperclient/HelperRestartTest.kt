package net.die.phoneapi.helperclient

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HelperRestartTest {
    @Test
    fun `backoff grows then caps`() {
        assertEquals(1_000L, restartDelayMs(0))
        assertEquals(2_000L, restartDelayMs(1))
        assertEquals(16_000L, restartDelayMs(4))
        assertEquals(30_000L, restartDelayMs(5))
        assertEquals(30_000L, restartDelayMs(9))
    }

    @Test
    fun `invalid ports are ignored`() {
        assertNull(parseAdbPort(null))
        assertNull(parseAdbPort(""))
        assertNull(parseAdbPort("0"))
        assertNull(parseAdbPort("65536"))
        assertNull(parseAdbPort("abc"))
        assertEquals(1, parseAdbPort("1"))
        assertEquals(5555, parseAdbPort(" 5555\n"))
        assertEquals(65_535, parseAdbPort("65535"))
    }
}
