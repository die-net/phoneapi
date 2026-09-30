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
    private var virtual: VirtualDisplay? = null
    private var token: IBinder? = null

    fun start(surface: Surface, width: Int, height: Int, displayId: Int) {
        stop()
        require(width >= 2 && height >= 2) { "mirror size ${width}x$height is too small" }
        require(width % 2 == 0 && height % 2 == 0) { "mirror size ${width}x$height must be even" }
        try {
            virtual = createVirtualDisplay(surface, width, height, displayId)
            Log.i(TAG, "Mirroring display $displayId at ${width}x$height via DisplayManager")
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "DisplayManager mirror is unavailable", unwrap(e))
            token = surfaceDisplay(surface, width, height, displayId)
            Log.i(TAG, "Mirroring display $displayId at ${width}x$height via SurfaceControl")
        }
    }

    fun stop() {
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
                false,
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
