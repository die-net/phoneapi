package net.die.phoneapi.stream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelayPictureTest {
    @Test
    fun `a mirror frame is drawn`() {
        assertEquals(RelayPicture.MIRROR, relayPicture(hasMirrorFrame = true, hasCapture = false))
        assertEquals(RelayPicture.MIRROR, relayPicture(hasMirrorFrame = true, hasCapture = true))
    }

    @Test
    fun `a screencap stands in`() {
        assertEquals(RelayPicture.CAPTURE, relayPicture(hasMirrorFrame = false, hasCapture = true))
    }

    @Test
    fun `nothing before either picture`() {
        assertEquals(RelayPicture.NONE, relayPicture(hasMirrorFrame = false, hasCapture = false))
    }
}
