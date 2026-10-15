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
    fun `a screencap stands in until the mirror draws`() {
        assertEquals(RelayPicture.CAPTURE, relayPicture(hasMirrorFrame = false, hasCapture = true))
    }

    @Test
    fun `nothing is drawn before either picture`() {
        assertEquals(RelayPicture.NONE, relayPicture(hasMirrorFrame = false, hasCapture = false))
    }
}
