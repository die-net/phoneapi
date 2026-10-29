package android.os

/**
 * Stands in for the platform `SystemClock` in JVM unit tests. The test classpath puts this ahead of
 * the Android stub jar, whose methods throw.
 */
object SystemClock {
    private const val NANOS_PER_MS = 1_000_000L

    @JvmStatic fun uptimeMillis(): Long = System.nanoTime() / NANOS_PER_MS
}
