package net.die.phoneapi.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnixSocketsTest {
    @Test
    fun `reads devtools sockets`() {
        val table =
            listOf(
                    "Num       RefCount Protocol Flags    Type St Inode Path",
                    "00000000: 00000002 00000000 00010000 0001 01 100 @chrome_devtools_remote",
                    "00000000: 00000002 00000000 00010000 0001 01 200 @webview_devtools_remote_44",
                    "00000000: 00000002 00000000 00010000 0001 01 300 @not_devtools",
                )
                .joinToString("\n")
        val sockets = parseDevtoolsSockets(table)
        assertEquals(
            listOf("chrome_devtools_remote", "webview_devtools_remote_44"),
            sockets.map { it.name },
        )
        assertEquals(listOf(100L, 200L), sockets.map { it.inode })
    }
}
