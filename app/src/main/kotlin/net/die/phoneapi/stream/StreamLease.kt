package net.die.phoneapi.stream

import android.content.Context
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import net.die.phoneapi.core.SettingsStore
import net.die.phoneapi.power.AwakeLease

/**
 * Holds the screen-on lease while at least one viewer is connected, and renews it as frames arrive.
 * The lease belongs to this process, so it cannot outlive the app.
 */
internal class StreamLease(context: Context, settings: SettingsStore) {
    private val lease = AwakeLease(context, settings)
    private val holders = AtomicInteger()
    private var lastMs = 0L

    fun opened() =
        synchronized(this) {
            holders.incrementAndGet()
            renew(force = true)
        }

    fun closed() =
        synchronized(this) {
            if (holders.decrementAndGet() == 0) lease.release()
        }

    fun renew(force: Boolean = false) {
        synchronized(this) {
            if (holders.get() <= 0) return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastMs < RENEW_MS) return
            lastMs = now
            lease.extend(HOLD_MS)
        }
    }

    private companion object {
        const val RENEW_MS = 60_000L
        const val HOLD_MS = 120_000L
    }
}
