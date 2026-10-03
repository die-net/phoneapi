package net.die.phoneapi.input

import android.os.RemoteException
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection

/** Key events through the helper's `InputManager.injectInputEvent`. */
class InjectKeyBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) {
    val name = "inject"

    val isAvailable: Boolean
        get() = helper.isRunning

    /** Sends a down/up pair, holding it past the long-press timeout if [longPress]. */
    suspend fun press(keyCode: Int, metaState: Int, longPress: Boolean): String {
        if (!isAvailable) throw ApiException.helperUnavailable()
        val down = SystemClock.uptimeMillis()
        var ok = send(KeyEvent(down, down, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        if (longPress) {
            delay(ViewConfiguration.getLongPressTimeout().toLong())
            val now = SystemClock.uptimeMillis()
            ok =
                ok &&
                    send(
                        KeyEvent.changeFlags(
                            KeyEvent(down, now, KeyEvent.ACTION_DOWN, keyCode, 1, metaState),
                            KeyEvent.FLAG_LONG_PRESS,
                        )
                    )
        }
        val up = SystemClock.uptimeMillis()
        ok = ok && send(KeyEvent(down, up, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        if (!ok) {
            throw ApiException(409, "key_failed", "The helper did not deliver the key")
        }
        return name
    }

    suspend fun send(event: KeyEvent): Boolean {
        val proxy = helper.require()
        return try {
            withContext(io) { proxy.injectKeyEvent(event, WAIT_FOR_FINISH) }
        } catch (e: RemoteException) {
            throw ApiException.helperDropped(e)
        }
    }

    private companion object {
        /** InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH. */
        const val WAIT_FOR_FINISH = 2
    }
}
