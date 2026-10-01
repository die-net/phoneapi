package net.die.phoneapi.helperclient

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.server.NetworkWatcher
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

/**
 * Brings the helper back after boot, a crash, or a package update.
 *
 * Shizuku is preferred when it is installed and the user has allowed it. Otherwise, on Android 11
 * and newer, a previously paired wireless-debugging key is used to run the same command the USB
 * flow runs. Android 10 stays on the USB command.
 */
internal class HelperSupervisor(
    private val context: Context,
    private val helper: HelperConnection,
    private val keys: KeystorePrivateKeyStore,
    private val network: NetworkWatcher,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val accessibilityComponent: String,
) {
    private val gate = Mutex()
    private val finder = AdbEndpointFinder(context, ioDispatcher)
    private val secure = SecureSettings(context.contentResolver)
    private var failures = 0
    private val shizukuArrived = Shizuku.OnBinderReceivedListener { nudge() }

    fun start() {
        // Loads the provider class the manifest declares, and tells Kadb where the ADB key lives.
        ShizukuProvider.enableMultiProcessSupport(false)
        KadbCert.configure(
            store = keys,
            policy = KadbCertPolicy(autoHealInvalidPrivateKey = false),
        )
        helper.onDied = { scope.launch { runAttempt() } }
        helper.onRejected = { scope.launch { runAttempt() } }
        listenForShizuku()
        scope.coroutineContext[Job]?.invokeOnCompletion { stopListeningForShizuku() }
        scope.launch {
            network.lanAddress.drop(1).collect { runAttempt(resetFailures = true) }
        }
        scope.launch { runAttempt() }
    }

    /** User-driven retry. Resets the backoff counter. */
    fun nudge() {
        scope.launch { runAttempt(resetFailures = true) }
    }

    fun shizukuAvailable(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Shizuku is not available", e)
            false
        }

    /** Asks Shizuku for permission when it is running, then starts the helper. */
    fun requestShizuku(): String {
        if (!shizukuAvailable()) return "Shizuku is not running."
        val granted =
            try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: IllegalStateException) {
                Log.i(TAG, "Shizuku is not available", e)
                return "Shizuku is not running."
            }
        if (granted) {
            nudge()
            return "Starting the helper with Shizuku."
        }
        val listener =
            object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (
                        requestCode == SHIZUKU_REQUEST &&
                            grantResult == PackageManager.PERMISSION_GRANTED
                    ) {
                        nudge()
                    }
                }
            }
        Shizuku.addRequestPermissionResultListener(listener)
        return try {
            Shizuku.requestPermission(SHIZUKU_REQUEST)
            "Allow PhoneAPI in Shizuku."
        } catch (e: IllegalStateException) {
            Shizuku.removeRequestPermissionResultListener(listener)
            Log.i(TAG, "Shizuku is not available", e)
            "Shizuku is not running."
        }
    }

    /** Pairs with the wireless-debugging dialog, then starts the helper. */
    @Suppress(
        "TooGenericExceptionCaught"
    ) // Pairing failures arrive as library-specific exceptions.
    suspend fun pair(code: String, port: Int?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Wireless debugging needs Android 11 or newer."
        }
        if (!PAIRING_CODE.matches(code)) return "Enter the 6-digit pairing code."
        if (port != null && port !in 1..65_535) {
            return "Enter the pairing port shown next to the code."
        }
        val endpoint = port ?: finder.pairingPort()
        if (endpoint == null) return "No pairing port. Enter the port shown next to the code."
        return try {
            pairWith(code, endpoint)
            keys.markPaired()
            nudge()
            "Paired. Starting the helper."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Wireless debugging pairing failed", e)
            "Pairing failed. Check the code and port, then try again."
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Kadb reports refused and auth failures as assorted exceptions.
    private suspend fun pairWith(code: String, port: Int) {
        val hosts = buildList {
            add("127.0.0.1")
            val lan = network.lanAddress.value?.hostAddress
            if (lan != null && lan != "127.0.0.1") add(lan)
        }
        var failure: Exception? = null
        for (host in hosts) {
            try {
                withContext(ioDispatcher) { Kadb.pair(host, port, code) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
                Log.w(TAG, "Pairing $host:$port failed", e)
            }
        }
        throw failure ?: error("Wireless debugging pairing failed")
    }

    private suspend fun runAttempt(resetFailures: Boolean = false) {
        var reset = resetFailures
        while (true) {
            val delayMs =
                gate.withLock {
                    if (reset) {
                        failures = 0
                        reset = false
                    }
                    doAttempt()
                } ?: return
            delay(delayMs)
        }
    }

    private suspend fun doAttempt(): Long? {
        if (secure.canWrite(context)) secure.healAccessibility(accessibilityComponent)
        if (helper.isRunning) return null
        val shizuku = shizukuGranted()
        val wireless = wirelessReady()
        if (!shizuku && !wireless) {
            helper.setStatus(idleStatus())
            return null
        }
        helper.setStatus(HelperStatus.STARTING)
        if (shizuku && tryShizuku()) return null
        if (wireless && restartOverWireless()) return null
        if (helper.isRunning) return null
        failures += 1
        if (failures >= MAX_FAILURES) {
            helper.setStatus(idleStatus())
            return null
        }
        return restartDelayMs(failures - 1)
    }

    private fun idleStatus(): HelperStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HelperStatus.NEEDS_PAIRING
        } else {
            HelperStatus.NEEDS_USB
        }

    private fun wirelessReady(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !keys.isPaired) return false
        return secure.wirelessEnabled() || secure.canWrite(context)
    }

    private fun shizukuGranted(): Boolean {
        if (!shizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Shizuku is not available", e)
            false
        }
    }

    @Suppress("TooGenericExceptionCaught") // Shizuku wraps binder failures in RuntimeException.
    private suspend fun tryShizuku(): Boolean {
        val connected = CompletableDeferred<Boolean>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val accepted = service != null && helper.register(service)
                    connected.complete(accepted)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    if (!connected.isCompleted) connected.complete(false)
                }
            }
        val args = userServiceArgs()
        var ok = false
        return try {
            Shizuku.bindUserService(args, connection)
            ok = withTimeoutOrNull(SHIZUKU_WAIT_MS) { connected.await() } == true
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(TAG, "Shizuku did not start the helper", e)
            false
        } finally {
            if (!ok) abandonShizuku(args, connection)
        }
    }

    @Suppress("TooGenericExceptionCaught") // Shizuku wraps binder failures in RuntimeException.
    private fun abandonShizuku(args: Shizuku.UserServiceArgs, connection: ServiceConnection) {
        try {
            Shizuku.unbindUserService(args, connection, true)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not unbind the Shizuku helper", e)
        }
    }

    private fun listenForShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuArrived)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Shizuku is not available", e)
        }
    }

    private fun stopListeningForShizuku() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuArrived)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Shizuku is not available", e)
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Shizuku.UserServiceArgs(ComponentName(context.packageName, SHELL_SERVICE))
            .daemon(true)
            .processNameSuffix("helper")
            .tag("phoneapi-helper")
            .version(info.longVersionCode.toInt())
            .debuggable(debuggable)
    }

    private suspend fun restartOverWireless(): Boolean {
        val wasOn = secure.wirelessEnabled()
        return try {
            if (!wasOn) secure.setWirelessEnabled(true)
            val port = finder.connectPort()
            if (port == null) {
                Log.w(TAG, "Wireless debugging did not publish a TLS port")
                false
            } else if (launchHelper(port) && waitUntilRunning()) {
                keys.markPaired()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not toggle wireless debugging", e)
            false
        } finally {
            if (!wasOn && secure.canWrite(context)) restoreWirelessOff()
        }
    }

    private fun restoreWirelessOff() {
        try {
            secure.setWirelessEnabled(false)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not turn wireless debugging back off", e)
        }
    }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Kadb reports auth and socket failures as assorted exceptions.
    private suspend fun launchHelper(port: Int): Boolean =
        withContext(ioDispatcher) {
            try {
                Kadb.create("127.0.0.1", port).use { kadb ->
                    val listed = kadb.shell(LIST_CMDLINES)
                    if (SHELL_SERVICE in listed.output || HELPER_MAIN in listed.output) {
                        Log.i(TAG, "Helper process is already running")
                        return@withContext true
                    }
                    val launched = kadb.shell(HelperLaunch.deviceCommand(context.packageName))
                    if (launched.exitCode != 0) {
                        Log.w(
                            TAG,
                            "Helper launch exited ${launched.exitCode}: ${launched.allOutput}",
                        )
                        false
                    } else {
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not launch the helper over wireless debugging", e)
                false
            }
        }

    private suspend fun waitUntilRunning(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + RUNNING_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (helper.isRunning) return true
            delay(POLL_MS)
        }
        return helper.isRunning
    }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val SHELL_SERVICE = "net.die.phoneapi.helper.ShellService"
        const val HELPER_MAIN = "net.die.phoneapi.helper.Main"
        const val SHIZUKU_REQUEST = 31
        const val MAX_FAILURES = 6
        const val SHIZUKU_WAIT_MS = 8_000L
        const val RUNNING_WAIT_MS = 10_000L
        const val POLL_MS = 200L
        val PAIRING_CODE = Regex("""\d{6}""")
        const val LIST_CMDLINES =
            """for c in /proc/[0-9]*/cmdline; do tr '\0' ' ' < "${'$'}c" 2>/dev/null; echo; done"""
    }
}
