package net.die.phoneapi.browser

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Turns `Accessibility.getFullAXTree` into the same kind of indented text the native snapshot uses.
 * Ignored nodes are skipped and their children promoted. Node ids are Chrome's, so a later tap can
 * address them.
 */
internal data class AxText(val title: String?, val compact: String)

internal fun formatAxTree(result: JsonObject, url: String? = null): AxText {
    val nodes = result["nodes"] as? JsonArray ?: return blank(url)
    return AxTree(nodes).format(url)
}

private fun blank(url: String?) =
    AxText(null, header(url, title = null, truncated = false).trimEnd())

private class AxTree(nodes: JsonArray) {
    private val byId = LinkedHashMap<String, JsonObject>()
    private val childrenOf = HashMap<String, List<String>>()
    private val claimed = HashSet<String>()

    init {
        for (element in nodes) add(element)
    }

    fun format(url: String?): AxText {
        val out = StringBuilder()
        val walk = Walk(this, out)
        walk.emitList(roots(), 0)
        val title = roots().firstOrNull()?.let { byId[it]?.stringValue("name") }
        return AxText(title, (header(url, title, walk.truncated) + out.toString()).trimEnd())
    }

    fun node(id: String): JsonObject? = byId[id]

    fun children(id: String): List<String> = childrenOf[id].orEmpty()

    fun ignored(node: JsonObject): Boolean =
        (node["ignored"] as? JsonPrimitive)?.booleanOrNull == true

    private fun roots(): List<String> = byId.keys.filter { it !in claimed }

    private fun add(element: JsonElement) {
        val node = element as? JsonObject ?: return
        val id = node.string("nodeId") ?: return
        byId[id] = node
        val children = node.childIds()
        childrenOf[id] = children
        claimed += children
    }
}

private class Walk(private val tree: AxTree, private val out: StringBuilder) {
    var lines = 0
    var truncated = false
    private val seen = HashSet<String>()

    fun emitList(ids: List<String>, depth: Int) {
        var index = 0
        while (index < ids.size && !truncated) {
            val run = textRun(ids, index)
            if (run == null) {
                emit(ids[index], depth)
                index++
            } else {
                appendRun(run, depth)
                index += run.size
            }
        }
    }

    fun emit(id: String, depth: Int) {
        if (!enter(id, depth)) return
        val node = tree.node(id) ?: return
        val ignored = tree.ignored(node)
        if (!ignored) append(node, id, depth, node.stringValue("name"))
        val nextDepth = if (ignored) depth else depth + 1
        emitList(tree.children(id), nextDepth)
    }

    private fun textRun(ids: List<String>, start: Int): List<String>? {
        if (!leafText(ids[start])) return null
        var end = start + 1
        while (end < ids.size && leafText(ids[end])) end++
        return ids.subList(start, end).takeIf { it.size > 1 }
    }

    private fun leafText(id: String): Boolean {
        val node = tree.node(id) ?: return false
        if (tree.ignored(node) || id in seen) return false
        if (node.stringValue("role") != "StaticText") return false
        return tree.children(id).isEmpty() && flags(node).isEmpty()
    }

    private fun appendRun(ids: List<String>, depth: Int) {
        val first = ids.first()
        if (!enter(first, depth)) return
        ids.drop(1).forEach { seen.add(it) }
        val node = tree.node(first) ?: return
        val name = ids.joinToString("") { tree.node(it)?.rawName().orEmpty() }.ifBlank { null }
        append(node, first, depth, name)
    }

    private fun enter(id: String, depth: Int): Boolean {
        if (truncated || !seen.add(id)) return false
        if (depth <= MAX_DEPTH) return true
        truncated = true
        return false
    }

    private fun append(node: JsonObject, id: String, depth: Int, name: String?) {
        if (lines >= MAX_LINES) {
            truncated = true
            return
        }
        out.append(line(node, id, depth, name)).append('\n')
        lines++
        if (lines >= MAX_LINES) truncated = true
    }
}

private fun header(url: String?, title: String?, truncated: Boolean): String = buildString {
    append("#")
    title?.let { append(" title=").append(quote(it)) }
    url?.let { append(" url=").append(it) }
    if (truncated) append(" truncated")
    append('\n')
}

private fun line(node: JsonObject, id: String, depth: Int, name: String?): String = buildString {
    repeat(depth) { append("  ") }
    append('[').append(id).append("] ")
    append(node.stringValue("role") ?: "node")
    name?.let { append(' ').append(quote(it)) }
    for (flag in flags(node)) append(' ').append(flag)
}

private fun flags(node: JsonObject): List<String> {
    val properties = node["properties"] as? JsonArray ?: return emptyList()
    return properties.mapNotNull { shownFlag(it) }
}

private fun shownFlag(element: JsonElement): String? {
    val property = element as? JsonObject ?: return null
    val name = property.string("name") ?: return null
    if (name !in SHOWN_FLAGS) return null
    val value = (property["value"] as? JsonObject)?.get("value") as? JsonPrimitive
    if (value?.booleanOrNull != true) return null
    return name
}

private fun JsonObject.childIds(): List<String> {
    val array = this["childIds"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.content }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringValue(name: String): String? = raw(name)?.takeIf { it.isNotBlank() }

private fun JsonObject.rawName(): String? = raw("name")

private fun JsonObject.raw(name: String): String? {
    val value = (this[name] as? JsonObject)?.get("value") ?: return null
    return (value as? JsonPrimitive)?.contentOrNull
}

private fun quote(text: String): String = buildString {
    append('"')
    for (ch in text) {
        when (ch) {
            '\\' -> append('\\').append('\\')
            '"' -> append('\\').append('"')
            '\n',
            '\r' -> append(' ')
            else -> append(ch)
        }
    }
    append('"')
}

private val SHOWN_FLAGS = setOf("focused", "checked", "disabled", "selected", "expanded")

private const val MAX_LINES = 400
private const val MAX_DEPTH = 40
