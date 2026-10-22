package net.die.phoneapi.browser

import net.die.phoneapi.core.ApiException

internal const val CHROME_SOCKET = "chrome_devtools_remote"
internal const val CHROME_PACKAGE = "com.android.chrome"

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

/** [url], trimmed, if PhoneAPI opens it in a tab: http(s) or about:blank. */
internal fun webUrl(url: String): String {
    val trimmed = url.trim()
    if (!acceptable(trimmed)) throw ApiException.badRequest("url must be http(s) or about:blank")
    return trimmed
}

private fun acceptable(url: String): Boolean {
    if (url.length > MAX_URL) return false
    if (url.indexOf('\r') >= 0 || url.indexOf('\n') >= 0) return false
    if (url == "about:blank") return true
    return url.startsWith("https://") || url.startsWith("http://")
}

private const val MAX_URL = 2_000
