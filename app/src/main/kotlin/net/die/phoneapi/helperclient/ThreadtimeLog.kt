package net.die.phoneapi.helperclient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `logcat -v threadtime` line. [time] is the logcat timestamp, not the receive time. */
internal data class ThreadtimeLine(
    val time: String,
    val pid: Long,
    val tid: Long,
    val level: String,
    val tag: String,
    val message: String,
)

internal fun ThreadtimeLine.json(): JsonObject = buildJsonObject {
    put("time", time)
    put("pid", pid)
    put("tid", tid)
    put("level", level)
    put("tag", tag)
    put("message", message)
}

internal object ThreadtimeLog {
    private val LINE =
        Regex(
            """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?):\s(.*)$"""
        )

    fun parse(line: String): ThreadtimeLine? {
        val match = LINE.matchEntire(line) ?: return null
        val time = match.groupValues[1]
        val pid = match.groupValues[2]
        val tid = match.groupValues[3]
        val level = match.groupValues[4]
        val tag = match.groupValues[5].trim()
        if (tag.isEmpty()) return null
        return ThreadtimeLine(
            time = time,
            pid = pid.toLong(),
            tid = tid.toLong(),
            level = level,
            tag = tag,
            message = match.groupValues[6],
        )
    }
}
