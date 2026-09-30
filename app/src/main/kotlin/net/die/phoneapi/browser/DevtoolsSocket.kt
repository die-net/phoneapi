package net.die.phoneapi.browser

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A connected DevTools socket. Closing it closes the underlying fd. */
internal interface DevtoolsSocket : Closeable {
    val input: InputStream
    val output: OutputStream
}
