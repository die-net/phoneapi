package net.die.phoneapi.input

import android.os.RemoteException
import android.view.KeyEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.helperclient.HelperConnection

/** Key events through the helper's `InputManager.injectInputEvent`. */
class InjectKeyBackend(
    private val helper: HelperConnection,
    private val io: CoroutineContext,
) : KeyBackend {
    override val name = "inject"

    override val isAvailable: Boolean
        get() = helper.isRunning

    override suspend fun send(event: KeyEvent): Boolean {
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
