package net.die.phoneapi.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.delay
import net.die.phoneapi.core.ApiException

/** Something that can deliver [KeyEvent]s. */
interface KeyBackend {
    val name: String

    val isAvailable: Boolean

    suspend fun send(event: KeyEvent): Boolean
}

/** Key events go through helper injection, including editing keys. */
class KeyBackends(val helper: KeyBackend) {
    fun forKey(keyCode: Int): KeyBackend {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) throw ApiException.badRequest("Unknown key")
        return helper.takeIf { it.isAvailable } ?: throw ApiException.helperUnavailable()
    }

    /** Sends a down/up pair, holding it past the long-press timeout if [longPress]. */
    suspend fun press(keyCode: Int, metaState: Int, longPress: Boolean): String {
        val backend = forKey(keyCode)
        val down = SystemClock.uptimeMillis()
        var ok = backend.send(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        if (longPress) {
            delay(ViewConfiguration.getLongPressTimeout().toLong())
            val now = SystemClock.uptimeMillis()
            ok =
                ok &&
                    backend.send(
                        KeyEvent.changeFlags(
                            KeyEvent(down, now, KeyEvent.ACTION_DOWN, keyCode, 1, metaState),
                            KeyEvent.FLAG_LONG_PRESS,
                        )
                    )
        }
        val up = SystemClock.uptimeMillis()
        ok = ok && backend.send(KeyEvent(down, up, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        if (!ok)
            throw ApiException(
                409,
                "key_failed",
                "The ${backend.name} backend did not deliver the key",
            )
        return backend.name
    }
}
