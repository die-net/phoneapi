package net.die.phoneapi.helperclient

import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.LogcatFilter
import net.die.phoneapi.core.argv
import net.die.phoneapi.model.Event
import net.die.phoneapi.model.EventTypes
import net.die.phoneapi.server.LogcatFeed

/**
 * One `logcat` process per collector. Closing the pipe when the flow is cancelled unblocks the read
 * on the IO dispatcher. Without a helper the flow completes empty.
 */
internal class HelperLogcatFeed(
    private val helper: HelperConnection,
    private val bus: EventBus,
    private val ioDispatcher: CoroutineDispatcher,
) : LogcatFeed {
    override fun lines(filter: LogcatFilter): Flow<Event> = callbackFlow {
        val outgoing = this
        open(filter)?.use { pipe ->
            val reader =
                launch(ioDispatcher) {
                    try {
                        read(pipe) { outgoing.send(it) }
                    } catch (e: IOException) {
                        Log.i(TAG, "logcat ended", e)
                    }
                }
            awaitClose { reader.cancel() }
        } ?: close()
    }

    private suspend fun open(filter: LogcatFilter): ParcelFileDescriptor? =
        withContext(ioDispatcher) {
            val remote = helper.getOrNull() ?: return@withContext null
            try {
                remote.logcat(filter.argv())
            } catch (e: RemoteException) {
                Log.w(TAG, "logcat unavailable", e)
                null
            }
        }

    private suspend fun read(pipe: ParcelFileDescriptor, emit: suspend (Event) -> Unit) {
        ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { reader ->
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: return
                val parsed = ThreadtimeLog.parse(line) ?: continue
                emit(bus.next(EventTypes.LOGCAT, parsed.json()))
            }
        }
    }

    private companion object {
        const val TAG = "PhoneApiLogcat"
    }
}
