package net.die.phoneapi.helperclient

/** Delay before the next automatic helper restart. Grows from 1s and stops at 30s. */
internal fun restartDelayMs(attempt: Int): Long {
    val shift = attempt.coerceIn(0, 5)
    return (1_000L shl shift).coerceAtMost(30_000L)
}

/** Parses an adbd TLS port. Empty, zero, and out-of-range values are ignored. */
internal fun parseAdbPort(raw: String?): Int? =
    raw?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 }
