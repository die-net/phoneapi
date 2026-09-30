package net.die.phoneapi.helperclient

import net.die.phoneapi.core.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ShellResultTest {
    @Test
    fun `parses the helper's json`() {
        val result = parseShellResult("""{"exit":0,"stdout":"Success\n","stderr":""}""")
        assertTrue(result.ok)
        assertEquals("Success\n", result.stdout)
    }

    @Test
    fun `defaults missing output`() {
        val result = parseShellResult("""{"exit":1}""")
        assertFalse(result.ok)
        assertEquals("", result.stderr)
    }

    @Test
    fun `rejects unparseable output`() {
        val e = assertThrows<ApiException> { parseShellResult("not json") }
        assertEquals(503, e.status)
        assertEquals("helper_error", e.error)
    }

    @Test
    fun `summarises failures`() {
        val result = ShellResult(exit = 255, stdout = "out", stderr = "boom\nmore detail")
        assertEquals("boom", result.failureMessage())
        assertEquals("out", result.copy(stderr = "  ").failureMessage())
        assertEquals("", ShellResult(exit = 1).failureMessage())
    }
}
