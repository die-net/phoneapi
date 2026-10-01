package net.die.phoneapi.browser

import kotlinx.serialization.Serializable
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
    val nodes = result.decodeCdp<NodeList>()?.nodes?.mapNotNull { it.decodeCdp<AxNode>() }
    if (nodes == null) return blank(url)
    return AxTree(nodes).format(url)
}

private fun blank(url: String?) =
    AxText(null, header(url, title = null, truncated = false).trimEnd())

private class AxTree(nodes: List<AxNode>) {
    private val byId = LinkedHashMap<String, AxNode>()
    private val childrenOf = HashMap<String, List<String>>()
    private val claimed = HashSet<String>()

    init {
        for (node in nodes) add(node)
    }

    fun format(url: String?): AxText {
        val out = StringBuilder()
        val walk = Walk(this, out)
        walk.emitList(roots(), 0)
        val title =
            roots().firstOrNull()?.let {
                byId[it]?.name.text()?.takeIf { name -> name.isNotBlank() }
            }
        return AxText(title, (header(url, title, walk.truncated) + out.toString()).trimEnd())
    }

    fun node(id: String): AxNode? = byId[id]

    fun children(id: String): List<String> = childrenOf[id].orEmpty()

    fun ignored(node: AxNode): Boolean = node.ignored

    private fun roots(): List<String> = byId.keys.filter { it !in claimed }

    private fun add(node: AxNode) {
        val id = node.nodeId ?: return
        byId[id] = node
        childrenOf[id] = node.childIds
        claimed += node.childIds
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
        if (!ignored) append(node, id, depth, node.name.text()?.takeIf { it.isNotBlank() })
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
        if (node.role.text() != "StaticText") return false
        return tree.children(id).isEmpty() && flags(node).isEmpty()
    }

    private fun appendRun(ids: List<String>, depth: Int) {
        val first = ids.first()
        if (!enter(first, depth)) return
        ids.drop(1).forEach { seen.add(it) }
        val node = tree.node(first) ?: return
        val name = ids.joinToString("") { tree.node(it)?.name.text().orEmpty() }.ifBlank { null }
        append(node, first, depth, name)
    }

    private fun enter(id: String, depth: Int): Boolean {
        if (truncated || !seen.add(id)) return false
        if (depth <= MAX_DEPTH) return true
        truncated = true
        return false
    }

    private fun append(node: AxNode, id: String, depth: Int, name: String?) {
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

private fun line(node: AxNode, id: String, depth: Int, name: String?): String = buildString {
    repeat(depth) { append("  ") }
    append('[').append(id).append("] ")
    append(node.role.text()?.takeIf { it.isNotBlank() } ?: "node")
    name?.let { append(' ').append(quote(it)) }
    for (flag in flags(node)) append(' ').append(flag)
}

private fun flags(node: AxNode): List<String> =
    node.properties.mapNotNull { property ->
        if (property.name !in SHOWN_FLAGS) return@mapNotNull null
        val value = property.value?.value as? JsonPrimitive ?: return@mapNotNull null
        if (value.booleanOrNull != true) return@mapNotNull null
        property.name
    }

private fun AxValue?.text(): String? {
    val value = this?.value as? JsonPrimitive ?: return null
    return value.contentOrNull
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

@Serializable private data class NodeList(val nodes: List<JsonElement> = emptyList())

@Serializable
private data class AxNode(
    val nodeId: String? = null,
    val ignored: Boolean = false,
    val role: AxValue? = null,
    val name: AxValue? = null,
    val childIds: List<String> = emptyList(),
    val properties: List<AxProperty> = emptyList(),
)

@Serializable private data class AxValue(val value: JsonElement? = null)

@Serializable private data class AxProperty(val name: String = "", val value: AxValue? = null)

private val SHOWN_FLAGS = setOf("focused", "checked", "disabled", "selected", "expanded")

private const val MAX_LINES = 400
private const val MAX_DEPTH = 40
