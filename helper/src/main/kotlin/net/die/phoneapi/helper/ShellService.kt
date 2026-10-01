package net.die.phoneapi.helper

import android.content.Context
import android.util.Log
import kotlin.concurrent.thread
import net.die.phoneapi.helper.compat.HiddenApi

/**
 * Shizuku user-service entry point. Shizuku loads this class by name from the APK, tries the
 * [Context] constructor first, and calls [destroy] when the service should stop.
 *
 * The process runs as root or shell, same as the USB helper. The app receives the binder from
 * Shizuku and also from the content-provider registration inside [HelperDaemon].
 */
internal class ShellService : HelperImpl {
    constructor() : super() {
        boot(null)
    }

    constructor(context: Context) : super() {
        boot(context.packageName)
    }

    private fun boot(packageName: String?) {
        HiddenApi.exempt()
        if (packageName != null) pinPackage(packageName)
        val classpath = System.getenv("CLASSPATH").orEmpty()
        thread(name = "phoneapi-helper", isDaemon = false) {
            HelperDaemon.serve(this, packageName, classpath)
        }
    }

    /** Shizuku calls this in-process when the user service is removed. */
    @Suppress("unused", "ExitOutsideMain")
    fun destroy() {
        Log.i(TAG, "Shizuku stopped the helper")
        System.exit(0)
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
    }
}
