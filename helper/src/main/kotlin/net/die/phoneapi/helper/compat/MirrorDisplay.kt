package net.die.phoneapi.helper.compat

import android.graphics.Rect
import android.hardware.display.VirtualDisplay
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Mirrors a display onto an encoder [Surface]. Prefers the hidden `DisplayManager` method shell can
 * call, and falls back to `SurfaceControl.createDisplay` where that method is absent.
 */
internal class MirrorDisplay {
    private val lock = Any()
    private var virtual: VirtualDisplay? = null
    private var token: IBinder? = null

    fun start(surface: Surface, width: Int, height: Int, displayId: Int) {
        synchronized(lock) {
            releaseLocked()
            Log.i(
                TAG,
                "diag: mirror start ${width}x$height secure=${diagSecure()} " +
                    "nudgeMs=${diagNudgeMs()} forceSurfaceControl=${diagForceSurfaceControl()}",
            )
            require(width >= 2 && height >= 2) { "mirror size ${width}x$height is too small" }
            require(width % 2 == 0 && height % 2 == 0) {
                "mirror size ${width}x$height must be even"
            }
            try {
                if (diagForceSurfaceControl()) throw NoSuchMethodException("diag: forced")
                virtual = createVirtualDisplay(surface, width, height, displayId)
                Log.i(TAG, "Mirroring display $displayId at ${width}x$height via DisplayManager")
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "DisplayManager mirror is unavailable", unwrap(e))
                token = surfaceDisplay(surface, width, height, displayId)
                Log.i(TAG, "Mirroring display $displayId at ${width}x$height via SurfaceControl")
                token?.let { scheduleNudge(it, displayId, width, height) }
            }
        }
    }

    fun stop() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        virtual?.release()
        virtual = null
        token?.let { destroyDisplay(it) }
        token = null
    }

    private fun createVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): VirtualDisplay {
        val manager = Class.forName("android.hardware.display.DisplayManager")
        val method =
            manager.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java,
            )
        val created =
            invoke(method, null, "phoneapi", width, height, displayId, surface) as? VirtualDisplay
        return created ?: error("createVirtualDisplay returned null")
    }

    private fun surfaceDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): IBinder =
        try {
            createSurfaceDisplay(surface, width, height, displayId)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException(unwrap(e).message ?: "Could not mirror the display", e)
        }

    private fun createSurfaceDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        displayId: Int,
    ): IBinder {
        val info = displayInfo(displayId)
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        val created =
            invoke(
                surfaceControl.getMethod(
                    "createDisplay",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ),
                null,
                "phoneapi",
                diagSecure(),
            )
                as? IBinder ?: error("createDisplay returned null")
        val device = Rect(0, 0, info.width, info.height)
        val target = Rect(0, 0, width, height)
        invoke(surfaceControl.getMethod("openTransaction"))
        try {
            invoke(
                surfaceControl.getMethod(
                    "setDisplaySurface",
                    IBinder::class.java,
                    Surface::class.java,
                ),
                null,
                created,
                surface,
            )
            invoke(
                surfaceControl.getMethod(
                    "setDisplayProjection",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                    Rect::class.java,
                    Rect::class.java,
                ),
                null,
                created,
                0,
                device,
                target,
            )
            invoke(
                surfaceControl.getMethod(
                    "setDisplayLayerStack",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                ),
                null,
                created,
                info.layerStack,
            )
        } finally {
            invoke(surfaceControl.getMethod("closeTransaction"))
        }
        return created
    }

    /**
     * Diagnostics: after [diagNudgeMs], projects onto a rect two pixels short, then back, so the
     * display changes twice without its content changing.
     */
    private fun scheduleNudge(display: IBinder, displayId: Int, width: Int, height: Int) {
        val delay = diagNudgeMs()
        if (delay <= 0) return
        Thread(
                {
                    Thread.sleep(delay)
                    synchronized(lock) {
                        if (token !== display) {
                            Log.i(TAG, "diag: nudge skipped, the mirror stopped first")
                            return@synchronized
                        }
                        try {
                            val device =
                                displayInfo(displayId).let { Rect(0, 0, it.width, it.height) }
                            project(display, device, Rect(0, 0, width, height - 2))
                            project(display, device, Rect(0, 0, width, height))
                            Log.i(TAG, "diag: nudged the mirror projection after ${delay}ms")
                        } catch (e: ReflectiveOperationException) {
                            Log.w(TAG, "diag: nudge failed", unwrap(e))
                        } catch (e: IllegalStateException) {
                            Log.w(TAG, "diag: nudge failed", e)
                        }
                    }
                },
                "phoneapi-mirror-nudge",
            )
            .start()
    }

    private fun project(display: IBinder, device: Rect, target: Rect) {
        val surfaceControl = Class.forName("android.view.SurfaceControl")
        invoke(surfaceControl.getMethod("openTransaction"))
        try {
            invoke(
                surfaceControl.getMethod(
                    "setDisplayProjection",
                    IBinder::class.java,
                    Int::class.javaPrimitiveType,
                    Rect::class.java,
                    Rect::class.java,
                ),
                null,
                display,
                0,
                device,
                target,
            )
        } finally {
            invoke(surfaceControl.getMethod("closeTransaction"))
        }
    }

    private fun diagSecure(): Boolean = prop("debug.phoneapi.mirror.secure") == "1"

    private fun diagForceSurfaceControl(): Boolean = prop("debug.phoneapi.mirror.sc") == "1"

    private fun diagNudgeMs(): Long = prop("debug.phoneapi.mirror.nudge_ms").toLongOrNull() ?: 0L

    private fun prop(name: String): String =
        try {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as String
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "diag: could not read $name", e)
            ""
        }

    private fun destroyDisplay(display: IBinder) {
        try {
            val surfaceControl = Class.forName("android.view.SurfaceControl")
            invoke(surfaceControl.getMethod("destroyDisplay", IBinder::class.java), null, display)
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "destroyDisplay failed", unwrap(e))
        }
    }

    private fun displayInfo(displayId: Int): DisplaySize {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = invoke(globalClass.getDeclaredMethod("getInstance"))
        val info =
            invoke(
                globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType),
                global,
                displayId,
            ) ?: error("Display $displayId was not found")
        val type = info.javaClass
        return DisplaySize(
            width = type.getField("logicalWidth").getInt(info),
            height = type.getField("logicalHeight").getInt(info),
            layerStack = type.getField("layerStack").getInt(info),
        )
    }

    private fun invoke(
        method: Method,
        target: Any? = null,
        vararg args: Any?,
    ): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw IllegalStateException(e.targetException.message ?: method.name, e)
        }

    private fun unwrap(error: ReflectiveOperationException): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    private data class DisplaySize(val width: Int, val height: Int, val layerStack: Int)

    private companion object {
        const val TAG = "PhoneApiHelper"
    }
}
