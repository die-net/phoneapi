package net.die.phoneapi.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextJsonTest {
    @Test
    fun `escapes control characters`() {
        val quote = '"'
        val slash = '\\'
        val encoded = jsonString("a$quote" + "b$slash" + "c" + '\n')
        assertEquals(""""a\"b\\c\n"""", encoded)
    }

    @Test
    fun `builds a shell result`() {
        val json = shellResultJson(0, "ok", "")
        assertEquals("""{"exit":0,"stdout":"ok","stderr":""}""", json)
    }

    @Test
    fun `omits unknown socket owners`() {
        val json = devtoolsJson(listOf(DevtoolsEntry("chrome_devtools_remote", pid = 4)))
        assertTrue(json.contains(""""name":"chrome_devtools_remote""""))
        assertTrue(json.contains(""""pid":4"""))
        assertTrue(!json.contains("uid"))
    }
}
