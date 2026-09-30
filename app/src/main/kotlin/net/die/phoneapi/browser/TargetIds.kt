package net.die.phoneapi.browser

import net.die.phoneapi.core.ApiException

internal const val CHROME_SOCKET = "chrome_devtools_remote"

/** One path segment: the socket name, then Chrome's target id. */
internal fun browserTargetId(socket: String, chromeId: String): String =
    "$socket$SEPARATOR$chromeId"

internal fun parseBrowserTargetId(id: String): Pair<String, String> {
    val split = id.indexOf(SEPARATOR)
    if (split <= 0 || split == id.lastIndex) {
        throw ApiException.badRequest("Unknown browser target")
    }
    return id.substring(0, split) to id.substring(split + 1)
}

private const val SEPARATOR = '~'
