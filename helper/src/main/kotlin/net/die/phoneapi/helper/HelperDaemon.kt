package net.die.phoneapi.helper

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log
import net.die.phoneapi.helper.compat.ProviderCalls

/**
 * Registers [helper] with the app until the installed APK disappears.
 *
 * Both the USB `app_process` entry point and the Shizuku user service use this, so a package update
 * makes the old process exit and the new app can start a fresh one.
 */
internal object HelperDaemon {
    private const val TAG = "PhoneApiHelper"
    private const val RETRY_MS = 5_000L

    fun serve(helper: HelperImpl, packageName: String?, classpath: String) {
        if (Looper.myLooper() == null) Looper.prepare()
        val looper = Looper.myLooper() ?: error("Looper was not prepared")
        val handler = Handler(looper)
        val tick =
            object : Runnable {
                override fun run() {
                    if (!helper.apkStillThere(classpath)) {
                        Log.i(TAG, "APK is gone; exiting so the updated app can relaunch us")
                        halt(0)
                    }
                    if (packageName != null) register(helper, packageName)
                    handler.postDelayed(this, RETRY_MS)
                }
            }
        tick.run()
        Log.i(TAG, "helper running pid=${Process.myPid()} pkg=${packageName ?: "unknown"}")
        Looper.loop()
    }

    @Suppress("TooGenericExceptionCaught") // The app may be down; the next tick retries.
    private fun register(helper: HelperImpl, packageName: String) {
        try {
            val extras = Bundle().apply { putBinder(Registration.EXTRA_BINDER, helper) }
            val authority = packageName + Registration.AUTHORITY_SUFFIX
            val result = ProviderCalls.call(authority, Registration.METHOD, extras)
            val uid = result?.getInt(Registration.EXTRA_APP_UID, -1) ?: -1
            if (uid <= 0) {
                Log.w(TAG, "App did not accept registration")
                return
            }
            helper.bindToApp(uid)
        } catch (e: RemoteException) {
            Log.w(TAG, "Registration failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } catch (e: RuntimeException) {
            // The app process may not be up yet, or may reject the caller. The next tick retries.
            Log.w(TAG, "Registration failed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }

    @Suppress("ExitOutsideMain") // This process is the helper, so exit codes are how it reports.
    private fun halt(code: Int): Nothing {
        System.exit(code)
        error("unreachable")
    }
}
