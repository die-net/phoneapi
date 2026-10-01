package net.die.phoneapi.helper

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PackageUidTest {
    @Test
    fun `reads the package uid`() {
        val output = "package:net.die.phoneapi uid:10213\n"
        assertEquals(10_213, parsePackageUid(output, "net.die.phoneapi"))
    }

    @Test
    fun `ignores a longer name`() {
        val output =
            listOf(
                    "package:net.die.phoneapi.helper uid:10001",
                    "package:net.die.phoneapi uid:10213",
                    "package:other uid:10002",
                )
                .joinToString(separator = System.lineSeparator())
        assertEquals(10_213, parsePackageUid(output, "net.die.phoneapi"))
    }

    @Test
    fun `ignores a missing uid`() {
        assertNull(parsePackageUid("package:net.die.phoneapi\n", "net.die.phoneapi"))
        assertNull(parsePackageUid("", "net.die.phoneapi"))
        assertNull(parsePackageUid("package:net.die.phoneapi uid:nope\n", "net.die.phoneapi"))
    }
}
