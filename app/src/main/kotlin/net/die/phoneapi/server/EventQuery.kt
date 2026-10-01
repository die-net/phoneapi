package net.die.phoneapi.server

import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.DEFAULT_LOG_LEVEL
import net.die.phoneapi.core.LOG_LEVELS
import net.die.phoneapi.core.LogcatFilter
import net.die.phoneapi.core.checkLogTag
import net.die.phoneapi.model.EventTypes

/**
 * `types` is null when the client did not send the parameter. [logcat] is set only for an exact
 * `logcat` type.
 */
internal data class EventQuery(val types: List<String>?, val logcat: LogcatFilter?)

internal fun parseEventQuery(types: String?, logcatTag: String?, logcatLevel: String?): EventQuery {
    val filters = types?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)
    logcatTag?.let { checkLogTag(it, "logcatTag") }
    if (logcatLevel != null && logcatLevel !in LOG_LEVELS) {
        throw ApiException.badRequest("logcatLevel must be V, D, I, W, E, or F")
    }
    val logcat =
        if (filters?.contains(EventTypes.LOGCAT) == true) {
            LogcatFilter(tag = logcatTag, level = logcatLevel ?: DEFAULT_LOG_LEVEL)
        } else {
            null
        }
    return EventQuery(filters, logcat)
}
