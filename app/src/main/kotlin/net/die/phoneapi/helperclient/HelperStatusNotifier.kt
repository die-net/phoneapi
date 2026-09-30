package net.die.phoneapi.helperclient

import android.Manifest
import android.R.drawable.stat_sys_warning
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.ui.MainActivity

/** Tells the person holding the phone when the helper has to be started from a computer. */
class HelperStatusNotifier(private val context: Context, private val helper: HelperConnection) {
    fun start(scope: CoroutineScope) {
        scope.launch { helper.status.collect { show(it) } }
    }

    private fun show(status: HelperStatus) {
        val manager = NotificationManagerCompat.from(context)
        if (status == HelperStatus.RUNNING || status == HelperStatus.STARTING) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (!notificationsAllowed()) return
        ensureChannel()
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(stat_sys_warning)
                .setContentTitle("PhoneAPI helper is not running")
                .setContentText(text(status))
                .setContentIntent(open)
                .addAction(
                    NotificationCompat.Action.Builder(
                            stat_sys_warning,
                            "Start helper",
                            startHelper(),
                        )
                        .build()
                )
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        if (
            status == HelperStatus.NEEDS_PAIRING && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            builder.addAction(pairAction())
        }
        val notification = builder.build()
        // notificationsAllowed() already checked POST_NOTIFICATIONS. Lint does not see that call.
        @SuppressLint("MissingPermission") manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startHelper(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, HelperStartReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pairAction(): NotificationCompat.Action {
        val input =
            RemoteInput.Builder(HelperStartReceiver.EXTRA_CODE)
                .setLabel("Wireless debugging code")
                .build()
        // RemoteInput results are written into the intent, so this pending intent cannot be
        // immutable.
        val reply =
            PendingIntent.getBroadcast(
                context,
                2,
                Intent(context, HelperStartReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
        return NotificationCompat.Action.Builder(stat_sys_warning, "Enter pairing code", reply)
            .addRemoteInput(input)
            .build()
    }

    private fun text(status: HelperStatus): String =
        when (status) {
            HelperStatus.NEEDS_USB -> "Start it over USB. The command is in the PhoneAPI app."
            HelperStatus.NEEDS_PAIRING ->
                "Open the wireless debugging pairing dialog, then enter its code here."
            HelperStatus.RUNNING,
            HelperStatus.STARTING,
            HelperStatus.STOPPED -> "The command is in the PhoneAPI app."
        }

    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Helper", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private companion object {
        const val CHANNEL = "helper"
        const val NOTIFICATION_ID = 7
    }
}
