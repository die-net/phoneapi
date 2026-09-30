package net.die.phoneapi.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.ApiException

/** PNG screenshots through [AccessibilityService.takeScreenshot] (Android 11+). */
class Screenshotter(
    private val service: StateFlow<AccessibilityService?>,
    private val ioDispatcher: CoroutineDispatcher,
    private val helperPng: suspend () -> ByteArray? = { null },
) {
    private val mutex = Mutex()
    private var lastMs = 0L

    suspend fun png(scale: Float = 1f): ByteArray {
        if (scale !in MIN_SCALE..1f) throw ApiException.badRequest("scale must be $MIN_SCALE..1")
        val fromHelper = helperBytes()
        if (fromHelper != null) return scalePng(fromHelper, scale)
        val svc = service.value ?: throw ApiException.a11yUnavailable()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw ApiException.unavailable(
                "unavailable",
                "Screenshots need Android 11 or later, or the shell helper",
            )
        }
        val bitmap = mutex.withLock {
            // The platform rejects screenshots taken closer together than this.
            val wait = lastMs + MIN_INTERVAL_MS - SystemClock.uptimeMillis()
            if (wait > 0) delay(wait)
            try {
                capture(svc)
            } finally {
                lastMs = SystemClock.uptimeMillis()
            }
        }
        return withContext(ioDispatcher) { encode(bitmap, scale) }
    }

    private suspend fun helperBytes(): ByteArray? {
        val bytes =
            try {
                helperPng()
            } catch (e: RemoteException) {
                Log.w(TAG, "Helper screenshot failed", e)
                null
            } ?: return null
        if (
            bytes.size < PNG_SIGNATURE.size ||
                !bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            Log.w(TAG, "Helper screenshot was not a PNG (${bytes.size} bytes)")
            return null
        }
        return bytes
    }

    private suspend fun scalePng(bytes: ByteArray, scale: Float): ByteArray {
        if (scale >= 1f) return bytes
        val bitmap =
            withContext(ioDispatcher) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                ?: throw ApiException.unavailable(
                    "screenshot_failed",
                    "Could not decode the helper PNG",
                )
        return withContext(ioDispatcher) { encode(bitmap, scale) }
    }

    private suspend fun capture(svc: AccessibilityService): Bitmap =
        suspendCancellableCoroutine { cont ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                svc.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    ioDispatcher.asExecutor(),
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) =
                            deliver(cont, result.hardwareBuffer, result.colorSpace)

                        override fun onFailure(errorCode: Int) = fail(cont, errorCode)
                    },
                )
            } else {
                fail(cont, ERROR_UNSUPPORTED)
            }
        }

    private fun deliver(
        cont: CancellableContinuation<Bitmap>,
        buffer: HardwareBuffer,
        colorSpace: ColorSpace,
    ) {
        val bitmap =
            try {
                Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.let(::toSoftware)
            } finally {
                buffer.close()
            }
        if (!cont.isActive) {
            bitmap?.recycle()
        } else if (bitmap == null) {
            cont.resumeWithException(
                ApiException.unavailable(
                    "screenshot_failed",
                    "Could not read the screenshot buffer",
                )
            )
        } else {
            cont.resume(bitmap)
        }
    }

    private fun toSoftware(hardware: Bitmap): Bitmap? {
        val copy = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        return copy
    }

    private fun fail(cont: CancellableContinuation<Bitmap>, errorCode: Int) {
        if (!cont.isActive) return
        val error =
            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                ApiException(429, "rate_limited", "Screenshots are rate-limited; retry shortly")
            } else if (errorCode == ERROR_SECURE_WINDOW) {
                ApiException(409, "secure_window", "A secure window prevents screenshots")
            } else {
                ApiException.unavailable(
                    "screenshot_failed",
                    "takeScreenshot failed with error $errorCode",
                )
            }
        cont.resumeWithException(error)
    }

    private fun encode(bitmap: Bitmap, scale: Float): ByteArray {
        val scaled =
            if (scale >= 1f) bitmap
            else
                bitmap.scale(
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                )
        try {
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "PhoneApiScreenshot"
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        const val MIN_SCALE = 0.05f
        const val MIN_INTERVAL_MS = 340L
        const val PNG_QUALITY = 100
        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34). */
        const val ERROR_SECURE_WINDOW = 6
        const val ERROR_UNSUPPORTED = -1
    }
}
