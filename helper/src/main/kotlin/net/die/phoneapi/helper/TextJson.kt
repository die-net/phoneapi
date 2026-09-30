package net.die.phoneapi.helper

/** Minimal JSON encoding for the helper's Binder replies. No serializer plugin in this module. */
internal fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else ->
                if (ch.code < HEX_ESCAPE_BELOW) {
                    out.append("\\u")
                    out.append(ch.code.toString(HEX).padStart(HEX_WIDTH, '0'))
                } else {
                    out.append(ch)
                }
        }
    }
    out.append('"')
    return out.toString()
}

internal fun shellResultJson(exit: Int, stdout: String, stderr: String): String =
    """{"exit":$exit,"stdout":${jsonString(stdout)},"stderr":${jsonString(stderr)}}"""

internal fun devtoolsJson(entries: List<DevtoolsEntry>): String =
    entries.joinToString(prefix = "[", postfix = "]") { entry ->
        val fields = ArrayList<String>(FIELDS)
        fields += "\"name\":${jsonString(entry.name)}"
        entry.pid?.let { fields += "\"pid\":$it" }
        entry.uid?.let { fields += "\"uid\":$it" }
        entry.packageName?.let { fields += "\"package\":${jsonString(it)}" }
        fields.joinToString(prefix = "{", postfix = "}")
    }

internal data class DevtoolsEntry(
    val name: String,
    val pid: Int? = null,
    val uid: Int? = null,
    val packageName: String? = null,
)

private const val HEX = 16
private const val HEX_WIDTH = 4
private const val HEX_ESCAPE_BELOW = 0x20
private const val FIELDS = 4
