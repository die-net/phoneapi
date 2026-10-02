package net.die.phoneapi.helperclient

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Reads and, when permitted, toggles wireless debugging. */
internal class SecureSettings(private val resolver: ContentResolver) {
    fun wirelessEnabled(): Boolean = Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1

    fun setWirelessEnabled(on: Boolean) {
        Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, if (on) 1 else 0)
    }

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
