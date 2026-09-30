package net.die.phoneapi.helperclient

import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.helper.IHelper
import net.die.phoneapi.model.EventTypes
import net.die.phoneapi.model.HelperStatus

/** Holds the helper's Binder once it registers, and tracks its liveness. */
class HelperConnection(private val bus: EventBus, private val idleStatus: HelperStatus) {
    private val statusFlow = MutableStateFlow(idleStatus)
    @Volatile private var helper: IHelper? = null
    @Volatile private var binder: IBinder? = null

    @Volatile
    var recoveredAtMs: Long? = null
        private set

    val status: StateFlow<HelperStatus> = statusFlow.asStateFlow()

    /** Runs after the helper process dies, once [status] has returned to the idle value. */
    var onDied: (() -> Unit)? = null

    private val deathRecipient = IBinder.DeathRecipient { onHelperDied() }

    private fun onHelperDied() {
        synchronized(this) {
            helper = null
            binder = null
        }
        Log.w(TAG, "Helper died")
        setStatus(idleStatus)
        onDied?.invoke()
    }

    val isRunning: Boolean
        get() = helper != null

    /** Returns the helper or throws `503 helper_unavailable`. */
    fun require(): IHelper = helper ?: throw ApiException.helperUnavailable()

    fun getOrNull(): IHelper? = helper

    @Synchronized
    fun register(newBinder: IBinder) {
        binder?.let { old -> runCatching { old.unlinkToDeath(deathRecipient, 0) } }
        val proxy = IHelper.Stub.asInterface(newBinder)
        newBinder.linkToDeath(deathRecipient, 0)
        val first = helper == null
        binder = newBinder
        helper = proxy
        if (first) {
            recoveredAtMs = System.currentTimeMillis()
            val pid = runCatching { proxy.pid() }.getOrDefault(-1)
            Log.i(TAG, "Helper registered (pid $pid)")
        }
        setStatus(HelperStatus.RUNNING)
    }

    fun setStatus(next: HelperStatus) {
        if (statusFlow.value == next) return
        statusFlow.value = next
        bus.emit(EventTypes.HELPER_STATUS, buildJsonObject { put("status", next.name.lowercase()) })
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
    }
}
