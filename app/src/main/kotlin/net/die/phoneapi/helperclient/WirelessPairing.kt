package net.die.phoneapi.helperclient

import android.R.drawable.stat_sys_warning
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.flyfishxu.kadb.mdns.KadbMdnsAndroid
import com.flyfishxu.kadb.mdns.MdnsEndpoint
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes the wireless-debugging pairing code from a heads-up notification.
 *
 * Android closes the pairing port as soon as its "Pair device with pairing code" dialog goes away,
 * which includes switching to another app, so the code can't be typed into PhoneAPI itself. After
 * [start], this watches mDNS for this phone's pairing service and asks for the code while the
 * dialog is showing.
 */
internal class WirelessPairing(
    private val context: Context,
    private val supervisor: HelperSupervisor,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private var search: Job? = null
    @Volatile private var port: Int? = null

    @Synchronized
    fun start() {
        search?.cancel()
        search = scope.launch {
            try {
                withTimeoutOrNull(SEARCH_MS) { watch() }
            } finally {
                port = null
                manager().cancel(NOTIFICATION_ID)
            }
        }
    }

    suspend fun submit(code: String) {
        post(notification("Pairing…", reply = false))
        val outcome = supervisor.pair(code, port)
        if (!outcome.paired) {
            post(notification(outcome.message, reply = true))
            return
        }
        synchronized(this) { search }?.cancelAndJoin()
        post(notification(outcome.message, reply = false).setTimeoutAfter(DONE_SHOWN_MS))
    }

    private suspend fun watch() =
        withContext(io) {
            KadbMdnsAndroid(context).use { mdns ->
                mdns.start()
                mdns.state
                    .map { ownPort(it.pairDevices) }
                    .distinctUntilChanged()
                    .collect { found ->
                        port = found
                        if (found == null) {
                            manager().cancel(NOTIFICATION_ID)
                        } else {
                            post(notification(ASK, reply = true))
                        }
                    }
            }
        }

    /** Other phones on the network may be pairing too; only this one's dialog counts. */
    private fun ownPort(found: List<MdnsEndpoint>): Int? {
        if (found.isEmpty()) return null
        val own = localAddresses()
        return found.firstOrNull { parse(it.host) in own }?.port
    }

    private fun notification(text: String, reply: Boolean): NotificationCompat.Builder {
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(stat_sys_warning)
                .setContentTitle("Wireless debugging code")
                .setContentText(text)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOnlyAlertOnce(true)
        if (reply) builder.addAction(replyAction())
        return builder
    }

    private fun replyAction(): NotificationCompat.Action {
        val input = RemoteInput.Builder(HelperStartReceiver.EXTRA_CODE).setLabel("Code").build()
        // RemoteInput results are written into the intent, so this pending intent cannot be
        // immutable.
        val reply =
            PendingIntent.getBroadcast(
                context,
                REPLY_REQUEST,
                Intent(context, HelperStartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return NotificationCompat.Action.Builder(stat_sys_warning, "Enter code", reply)
            .addRemoteInput(input)
            .build()
    }

    private fun post(builder: NotificationCompat.Builder) {
        if (!context.notificationsAllowed()) return
        ensureChannel()
        // notificationsAllowed() already checked POST_NOTIFICATIONS. Lint does not see that call.
        @SuppressLint("MissingPermission") manager().notify(NOTIFICATION_ID, builder.build())
    }

    private fun manager() = NotificationManagerCompat.from(context)

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        // High importance so it drops down over the Settings dialog instead of hiding in the shade.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Wireless debugging pairing",
                NotificationManager.IMPORTANCE_HIGH,
            )
        )
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val CHANNEL = "wireless_pairing"
        const val NOTIFICATION_ID = 8
        const val REPLY_REQUEST = 2
        const val SEARCH_MS = 5 * 60_000L
        const val DONE_SHOWN_MS = 5_000L
        const val ASK = "Type the 6-digit code from the pairing dialog."

        fun parse(host: String): InetAddress? =
            try {
                InetAddress.getByName(host)
            } catch (e: UnknownHostException) {
                Log.w(TAG, "Unparseable mDNS host $host", e)
                null
            }

        fun localAddresses(): Set<InetAddress> =
            try {
                NetworkInterface.getNetworkInterfaces()
                    ?.asSequence()
                    ?.flatMap { it.inetAddresses.asSequence() }
                    ?.toSet()
                    .orEmpty()
            } catch (e: SocketException) {
                Log.w(TAG, "Could not list network interfaces", e)
                emptySet()
            }
    }
}
