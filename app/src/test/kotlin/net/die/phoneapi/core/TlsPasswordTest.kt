package net.die.phoneapi.core

import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TlsPasswordTest {
    @Test
    fun `strips a legacy password`() {
        val original =
            """{"port":23456,"bindMode":"ALL","mdnsEnabled":false,"mdnsName":"Phone","keepAwakeMs":1000,"keystorePassword":"s3cret","instanceId":"abc123","future":true}"""
        assertEquals("s3cret", legacyKeystorePassword(original))
        val stripped = stripKeystorePassword(original)
        assertNull(legacyKeystorePassword(stripped))
        assertEquals(stripped, stripKeystorePassword(stripped))
        assertTrue(stripped.contains("\"future\":true"))
        val settings = ApiJson.decodeFromString(serializer<Settings>(), stripped)
        assertEquals(23_456, settings.port)
        assertEquals(BindMode.ALL, settings.bindMode)
        assertFalse(settings.mdnsEnabled)
        assertEquals("Phone", settings.mdnsName)
        assertEquals(1000, settings.keepAwakeMs)
        assertEquals("abc123", settings.instanceId)
        assertFalse(
            ApiJson.encodeToString(serializer<Settings>(), settings).contains("keystorePassword")
        )
    }

    @Test
    fun `legacy settings still decode`() {
        val settings =
            ApiJson.decodeFromString(
                serializer<Settings>(),
                """{"port":9,"keystorePassword":"pw","instanceId":"id"}""",
            )
        assertEquals(9, settings.port)
        assertEquals(BindMode.LAN, settings.bindMode)
        assertEquals("id", settings.instanceId)
        assertTrue(settings.mdnsEnabled)
        assertEquals("Android device", settings.mdnsName)
    }

    @Test
    fun `ignores a bad password`() {
        assertNull(legacyKeystorePassword("""{"port":1}"""))
        assertNull(legacyKeystorePassword("""{"keystorePassword":""}"""))
        assertNull(legacyKeystorePassword("""{"keystorePassword":1}"""))
        assertNull(legacyKeystorePassword("not json"))
        assertNull(legacyKeystorePassword("[]"))
        assertEquals("not json", stripKeystorePassword("not json"))
        val same = """{"port":1,"instanceId":"id"}"""
        assertEquals(same, stripKeystorePassword(same))
    }
}
