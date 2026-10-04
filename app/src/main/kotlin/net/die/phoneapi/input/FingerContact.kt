package net.die.phoneapi.input

import net.die.phoneapi.helper.AxisRange
import net.die.phoneapi.helper.TouchscreenInfo

/**
 * The ellipse [InjectTouchBackend] stamps on every injected touch. CDP touches use the same
 * numbers, converted the way Chrome turns a MotionEvent into a touch: radius is half the major or
 * minor axis in CSS pixels, force is pressure, and rotation is orientation in degrees.
 */
internal data class FingerContact(
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val orientation: Float,
) {
    fun toCdp(dipScale: Float): CdpContact {
        val scale = dipScale.takeIf { it > 0f } ?: 1f
        return CdpContact(
            radiusX = touchMajor / 2f / scale,
            radiusY = touchMinor / 2f / scale,
            force = pressure,
            rotationAngle = Math.toDegrees(orientation.toDouble()).toFloat(),
        )
    }

    companion object {
        /** Matches the helper's fallback touchscreen, whose axes run from 0 to 1 (orientation 0). */
        val fallback: FingerContact = from(fallbackScreen())

        fun from(screen: TouchscreenInfo): FingerContact =
            FingerContact(
                pressure = along(screen.pressure, PRESSURE_FRACTION),
                size = along(screen.size, SIZE_FRACTION),
                touchMajor = along(screen.touchMajor, SIZE_FRACTION),
                touchMinor = along(screen.touchMinor, SIZE_FRACTION),
                orientation = along(screen.orientation, CENTER_FRACTION),
            )
    }
}

/** Fields of one `Input.dispatchTouchEvent` touch point, in CSS pixels. */
internal data class CdpContact(
    val radiusX: Float,
    val radiusY: Float,
    val force: Float,
    val rotationAngle: Float,
)

private fun along(range: AxisRange?, fraction: Float): Float {
    if (range == null || range.max <= range.min) return range?.min ?: 0f
    return range.min + (range.max - range.min) * fraction
}

private fun fallbackScreen(): TouchscreenInfo =
    TouchscreenInfo().apply {
        pressure = axis(0f, 1f)
        size = axis(0f, 1f)
        touchMajor = axis(0f, 1f)
        touchMinor = axis(0f, 1f)
        orientation = axis(0f, 0f)
    }

private fun axis(min: Float, max: Float): AxisRange =
    AxisRange().apply {
        this.min = min
        this.max = max
    }

/** A point [fraction] of the way from min to max, so a finger is not the exact center of the range. */
private const val PRESSURE_FRACTION = 0.55f
private const val SIZE_FRACTION = 0.08f
private const val CENTER_FRACTION = 0.5f
