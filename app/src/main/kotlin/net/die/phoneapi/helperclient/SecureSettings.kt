package net.die.phoneapi.helperclient

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** Reads and, when permitted, toggles wireless debugging and the accessibility service. */
internal class SecureSettings(private val resolver: ContentResolver) {
    fun wirelessEnabled(): Boolean = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1

    fun setWirelessEnabled(on: Boolean) {
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
    }

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Turns the accessibility service back on after a reboot or package update. */
    fun healAccessibility(component: String) {
        val current =
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
        val parts = current.split(':').filter { it.isNotEmpty() }
        if (component in parts) return
        val next = if (parts.isEmpty()) component else parts.plus(component).joinToString(":")
        try {
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                next,
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Re-enabled the accessibility service")
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not re-enable the accessibility service", e)
        }
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
