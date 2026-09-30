package net.die.phoneapi.helper

import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.io.File
import net.die.phoneapi.helper.compat.AudioTap
import net.die.phoneapi.helper.compat.InputInjector
import net.die.phoneapi.helper.compat.MirrorDisplay

/** [IHelper] implementation. Every privileged call drops the app's Binder identity first. */
internal open class HelperImpl : IHelper.Stub() {
    @Volatile private var appUid: Int = UNKNOWN_UID
    private val injector by lazy { InputInjector() }
    private val mirror = MirrorDisplay()
    private val audio = AudioTap()

    fun bindToApp(uid: Int) {
        appUid = uid
    }

    override fun protocolVersion(): Int = PROTOCOL

    override fun pid(): Int = Process.myPid()

    override fun injectMotionEvent(event: MotionEvent, mode: Int): Boolean =
        privileged("injectMotion") {
            try {
                injector.inject(event, mode)
            } finally {
                event.recycle()
            }
        }

    override fun injectKeyEvent(event: KeyEvent, mode: Int): Boolean =
        privileged("injectKey") { injector.inject(event, mode) }

    override fun touchscreenInfo(): String = privileged("touchscreen") { touchscreenJson() }

    @Suppress("MissingUseCall") // The app reads this descriptor; closing it here would break that.
    override fun openAbstractSocket(name: String): ParcelFileDescriptor =
        privileged("socket") { AbstractSockets.connect(name) }

    override fun listDevtoolsSockets(): String =
        privileged("sockets") { AbstractSockets.devtools() }

    override fun exec(argv: Array<out String>, timeoutMs: Long, maxOutputBytes: Int): String =
        privileged("exec") {
            if (argv.isEmpty()) throw RemoteException("argv is empty")
            Commands.exec(argv.toList(), timeoutMs, maxOutputBytes)
        }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun logcat(args: Array<out String>): ParcelFileDescriptor =
        privileged("logcat") { Commands.pipe(listOf("logcat") + args) }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun screencap(): ParcelFileDescriptor =
        privileged("screencap") { Commands.pipe(listOf("screencap", "-p")) }

    override fun startMirror(surface: Surface, width: Int, height: Int, displayId: Int): Boolean =
        privileged("mirror") {
            mirror.start(surface, width, height, displayId)
            true
        }

    override fun stopMirror() {
        privileged("mirror-stop") { mirror.stop() }
    }

    @Suppress("MissingUseCall") // The app reads this pipe; closing it here would break that.
    override fun startAudioCapture(sampleRate: Int, channels: Int): ParcelFileDescriptor? =
        privileged("audio") { audio.start(sampleRate, channels) }

    override fun stopAudioCapture() {
        privileged("audio-stop") { audio.stop() }
    }

    @Suppress("ExitOutsideMain") // The app asked this process to stop.
    override fun shutdown() {
        enforceCaller()
        Log.i(TAG, "shutdown")
        System.exit(0)
    }

    private fun enforceCaller() {
        val uid = Binder.getCallingUid()
        if (uid == Process.ROOT_UID) return
        // Shizuku delivers the binder straight to the app, which never gets a chance to tell us
        // its uid through the content provider. The first app caller becomes the bound uid.
        if (appUid == UNKNOWN_UID && uid >= Process.FIRST_APPLICATION_UID) appUid = uid
        if (uid != appUid) throw SecurityException("caller uid $uid")
    }

    @Suppress("TooGenericExceptionCaught") // Log binder failures, then rethrow them to the app.
    private inline fun <T> privileged(what: String, block: () -> T): T {
        enforceCaller()
        val token = Binder.clearCallingIdentity()
        try {
            return block()
        } catch (e: RuntimeException) {
            Log.e(TAG, what, e)
            throw e
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun touchscreenJson(): String {
        val touch = ArrayList<InputDevice>()
        for (id in InputDevice.getDeviceIds()) {
            val candidate = InputDevice.getDevice(id) ?: continue
            if (candidate.sources and InputDevice.SOURCE_TOUCHSCREEN != 0) touch += candidate
        }
        val device = touch.firstOrNull { !it.isVirtual } ?: touch.firstOrNull()
        if (device == null) return "null"
        return buildString {
            append("""{"deviceId":${device.id},"source":${InputDevice.SOURCE_TOUCHSCREEN}""")
            append(""","maxX":${axisMax(device, MotionEvent.AXIS_X)}""")
            append(""","maxY":${axisMax(device, MotionEvent.AXIS_Y)}""")
            append(""","pressure":${axisRange(device, MotionEvent.AXIS_PRESSURE)}""")
            append(""","touchMajor":${axisRange(device, MotionEvent.AXIS_TOUCH_MAJOR)}""")
            append(""","touchMinor":${axisRange(device, MotionEvent.AXIS_TOUCH_MINOR)}""")
            append(""","orientation":${axisRange(device, MotionEvent.AXIS_ORIENTATION)}""")
            append(""","size":${axisRange(device, MotionEvent.AXIS_SIZE)}}""")
        }
    }

    private fun axisRange(device: InputDevice, axis: Int): String {
        val range = device.getMotionRange(axis)
        val min = range?.min ?: 0f
        val max = range?.max ?: 1f
        return """{"min":$min,"max":$max}"""
    }

    private fun axisMax(device: InputDevice, axis: Int): Float =
        device.getMotionRange(axis)?.max ?: 0f

    fun apkStillThere(classpath: String): Boolean =
        classpath.split(':').filter { it.isNotEmpty() }.all { File(it).exists() }

    private companion object {
        const val TAG = "PhoneApiHelper"
        const val PROTOCOL = 1
        const val UNKNOWN_UID = -1
    }
}
