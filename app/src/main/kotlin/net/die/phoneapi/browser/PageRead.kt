package net.die.phoneapi.browser

import java.net.URI
import java.net.URISyntaxException
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.core.ApiJson
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.PageHeading
import net.die.phoneapi.model.PageLink
import net.die.phoneapi.model.ReadFormat
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.TextMatch

/**
 * What `page-read.js` returns: page facts, and the content as blocks. Each block is a heading
 * (`h`), paragraph (`p`), list item (`li`), preformatted text (`pre`), table row (`tr`), or rule
 * (`hr`). Block text is a list of runs, each with an optional link and style.
 */
@Serializable
internal data class PageExtract(
    val missing: Boolean = false,
    /** Set by PhoneAPI, not the script: Chrome had unloaded the tab, so it was reloaded. */
    val reloaded: Boolean = false,
    val title: String? = null,
    val url: String? = null,
    val readyState: String? = null,
    /** `document.visibilityState`, which can say `visible` while Chrome itself is hidden. */
    val visibility: String? = null,
    val pending: List<String> = emptyList(),
    val status: Int = 0,
    val published: String? = null,
    val blocked: String? = null,
    val pageChars: Int = 0,
    val links: Int = 0,
    val tables: Int = 0,
    val forms: Int = 0,
    val extracted: String = "page",
    val clipped: Boolean = false,
    val blocks: List<PageBlock> = emptyList(),
)

@Serializable
internal data class PageBlock(
    val k: String,
    /** Heading level. */
    val l: Int = 0,
    /** List depth, from 1. */
    val d: Int = 1,
    /** Number of an ordered list item. */
    val o: Int? = null,
    /** Inside a block quote when 1. */
    val q: Int = 0,
    /** Table this row belongs to. */
    val t: Int = 0,
    /** Every cell in this row is a header cell. */
    val th: Boolean = false,
    /** Table cells. */
    val c: List<String> = emptyList(),
    /** Preformatted text. */
    val x: String = "",
    /** Text runs. */
    val r: List<PageRun> = emptyList(),
)

@Serializable
internal data class PageRun(
    val t: String,
    /** Link target. */
    val h: String? = null,
    /** Style: b, i, or code. */
    val s: String? = null,
)

/** A link in rendered content. */
internal data class RenderedLink(val text: String, val href: String, val offset: Int)

/** Content rendered from blocks, with where its headings and links start. */
internal data class RenderedPage(
    val text: String,
    val headings: List<PageHeading>,
    val links: List<RenderedLink>,
) {
    /** A short hash of [text], so a caller can tell when offsets from another call still apply. */
    val version: String by lazy {
        val crc = CRC32()
        crc.update(text.toByteArray())
        crc.value.toString(HEX).padStart(CRC_DIGITS, '0')
    }
}

internal fun renderPage(blocks: List<PageBlock>, format: ReadFormat): RenderedPage =
    PageRenderer(format == ReadFormat.MARKDOWN).render(blocks)

private class PageRenderer(private val markdown: Boolean) {
    private val out = StringBuilder()
    private val headings = ArrayList<PageHeading>()
    private val links = ArrayList<RenderedLink>()
    private var previous: PageBlock? = null

    fun render(blocks: List<PageBlock>): RenderedPage {
        for (block in blocks) block(block)
        return RenderedPage(out.toString(), headings, links)
    }

    private fun block(block: PageBlock) {
        val inline = if (block.r.isEmpty()) null else inline(block.r)
        val text = body(block, inline)
        if (text.isNullOrBlank()) return
        val prefix = prefix(block)
        separate(block)
        val start = out.length
        out.append(prefix).append(text)
        if (block.k == "h") headings += PageHeading(block.l, inline?.text.orEmpty(), start)
        inline?.links?.forEach { links += it.copy(offset = start + prefix.length + it.offset) }
        previous = block
    }

    /** The block's text without its prefix, or null when it renders as nothing. */
    private fun body(block: PageBlock, inline: Inline?): String? =
        when (block.k) {
            "pre" -> if (markdown) "```\n${block.x}\n```" else block.x
            "tr" -> if (block.c.all { it.isBlank() }) null else row(block)
            "hr" -> if (markdown) "---" else null
            else -> inline?.text
        }

    private fun prefix(block: PageBlock): String {
        val quote = if (markdown && block.q == 1 && block.k != "pre") "> " else ""
        return quote +
            when (block.k) {
                "h" -> if (markdown) "#".repeat(block.l.coerceIn(1, MAX_HEADING)) + " " else ""
                "li" ->
                    "  ".repeat((block.d - 1).coerceAtLeast(0)) + (block.o?.let { "$it. " } ?: "- ")
                else -> ""
            }
    }

    private fun separate(block: PageBlock) {
        val last = previous ?: return
        val tight =
            (last.k == "li" && block.k == "li") ||
                (last.k == "tr" && block.k == "tr" && last.t == block.t)
        out.append(if (tight) "\n" else "\n\n")
    }

    private fun row(block: PageBlock): String {
        if (!markdown) return block.c.joinToString(" | ")
        val cells = block.c.map { it.replace("|", "\\|") }
        val line = cells.joinToString(" | ", prefix = "| ", postfix = " |")
        val first = previous?.let { it.k != "tr" || it.t != block.t } != false
        if (!first) return line
        return line + "\n" + cells.joinToString(" | ", prefix = "| ", postfix = " |") { "---" }
    }

    private fun inline(runs: List<PageRun>): Inline {
        val text = StringBuilder()
        val found = ArrayList<RenderedLink>()
        for (run in runs) appendRun(run, text, found)
        return Inline(text.toString().trimEnd(), found)
    }

    /**
     * Appends [run] to [text], keeping at most one space between words and moving spaces outside
     * markdown marks, and records where a link's text starts.
     */
    private fun appendRun(run: PageRun, text: StringBuilder, found: MutableList<RenderedLink>) {
        val raw = if (text.isEmpty() || text.last() == ' ') run.t.trimStart() else run.t
        val core = raw.trim()
        if (core.isEmpty()) {
            if (raw.isNotEmpty() && text.isNotEmpty() && text.last() != ' ') text.append(' ')
            return
        }
        val mark = if (markdown) MARKS[run.s].orEmpty() else ""
        if (raw.first() == ' ') text.append(' ')
        text.append(mark)
        run.h?.let { found += RenderedLink(core, it, text.length) }
        text.append(core).append(mark)
        if (raw.last() == ' ') text.append(' ')
    }

    private data class Inline(val text: String, val links: List<RenderedLink>)

    private companion object {
        val MARKS = mapOf("b" to "**", "i" to "*", "code" to "`")
    }
}

internal fun checkRead(request: BrowserReadRequest) {
    if (request.maxChars !in 1..MAX_READ_CHARS) {
        throw ApiException.badRequest("maxChars must be 1 to $MAX_READ_CHARS")
    }
    checkSelector(request.selector)
}

internal fun checkFind(request: BrowserFindRequest) {
    if (request.limit !in 1..MAX_FIND_RESULTS) {
        throw ApiException.badRequest("limit must be 1 to $MAX_FIND_RESULTS")
    }
    if (request.contextChars !in 0..MAX_CONTEXT_CHARS) {
        throw ApiException.badRequest("contextChars must be 0 to $MAX_CONTEXT_CHARS")
    }
    checkSelector(request.selector)
}

private fun checkSelector(selector: String?) {
    if (selector != null && (selector.isBlank() || selector.length > MAX_SELECTOR)) {
        throw ApiException.badRequest("selector must be 1..$MAX_SELECTOR characters")
    }
}

/** The expression that runs [source] (Readability.js and page-read.js) with these options. */
internal fun readScript(source: String, scope: ReadScope, selector: String?): String {
    val options = buildJsonObject {
        put("scope", if (scope == ReadScope.ARTICLE) "article" else "page")
        selector?.let { put("selector", it) }
    }
    return "(function (options) {\n$source\n})($options)"
}

/** Decodes what [readScript] returned, turning its failures into API errors. */
internal fun decodeExtract(result: EvalResult, selector: String?): PageExtract {
    result.exception?.let { message ->
        if (selector != null && "not a valid selector" in message) {
            throw ApiException.badRequest("selector is not valid CSS: $message")
        }
        throw ApiException(BAD_GATEWAY, "page_error", "Reading the page failed: $message")
    }
    val value =
        result.value as? JsonObject
            ?: throw ApiException(BAD_GATEWAY, "page_error", "Reading the page returned nothing")
    val extract = ApiJson.decodeFromJsonElement(PageExtract.serializer(), value)
    if (extract.missing) throw ApiException.notFound("No element matches the selector")
    return extract
}

internal fun readPage(extract: PageExtract, request: BrowserReadRequest): BrowserReadResult {
    val page = renderPage(extract.blocks, request.format)
    val total = page.text.length
    if (request.offset !in 0..total) {
        throw ApiException.badRequest("offset must be 0 to $total, the content's length")
    }
    val end =
        if (request.outline) request.offset
        else cutPoint(page.text, request.offset, request.maxChars)
    return BrowserReadResult(
        title = extract.title,
        url = extract.url,
        published = extract.published,
        readyState = extract.readyState,
        status = extract.status.takeIf { it > 0 },
        blocked = extract.blocked,
        sparse = extract.pageChars < SPARSE_CHARS,
        pending = extract.pending,
        extracted = extract.extracted,
        version = page.version,
        totalChars = total,
        offset = request.offset,
        returnedChars = end - request.offset,
        nextOffset = end.takeIf { it < total && !request.outline },
        truncated = !request.outline && (request.offset > 0 || end < total),
        clipped = extract.clipped,
        content = if (request.outline) null else page.text.substring(request.offset, end),
        headings = if (request.outline) page.headings else null,
        links = extract.links,
        tables = extract.tables,
        forms = extract.forms,
    )
}

/**
 * Where a slice of up to [maxChars] from [start] should end: at the last line break in its second
 * half, else the last space there, else at [maxChars], never inside a surrogate pair.
 */
internal fun cutPoint(text: String, start: Int, maxChars: Int): Int {
    val limit = start + maxChars
    if (limit >= text.length) return text.length
    val floor = start + maxChars / 2
    val newline = text.lastIndexOf('\n', limit - 1)
    if (newline >= floor) return newline + 1
    val space = text.lastIndexOf(' ', limit - 1)
    if (space >= floor) return space + 1
    return if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
}

internal fun findInPage(extract: PageExtract, request: BrowserFindRequest): BrowserFindResult {
    val page = renderPage(extract.blocks, request.format)
    val base =
        BrowserFindResult(
            title = extract.title,
            url = extract.url,
            extracted = extract.extracted,
            version = page.version,
            totalChars = page.text.length,
            total = 0,
        )
    return when (request.kind) {
        FindKind.TEXT -> findText(page, request, base)
        FindKind.LINKS -> findLinks(page, request, base, extract.url)
    }
}

@Suppress("SwallowedException") // The error message says what is wrong with the pattern.
private fun findText(
    page: RenderedPage,
    request: BrowserFindRequest,
    base: BrowserFindResult,
): BrowserFindResult {
    val query =
        request.query?.takeIf { it.isNotEmpty() }
            ?: throw ApiException.badRequest("query is required to find text")
    val pattern =
        try {
            Regex(if (request.regex) query else Regex.escape(query), RegexOption.IGNORE_CASE)
        } catch (e: IllegalArgumentException) {
            throw ApiException.badRequest(
                "query is not a valid regular expression: ${e.message.orEmpty()}"
            )
        }
    val text = page.text
    val hits = pattern.findAll(text).filter { !it.range.isEmpty() }.take(MAX_COUNTED).toList()
    val matches =
        hits.take(request.limit).map { hit ->
            val start = hit.range.first
            val end = hit.range.last + 1
            TextMatch(
                offset = start,
                heading = page.headings.lastOrNull { it.offset <= start }?.text,
                before = text.substring((start - request.contextChars).coerceAtLeast(0), start),
                match = hit.value,
                after = text.substring(end, (end + request.contextChars).coerceAtMost(text.length)),
            )
        }
    return base.copy(total = hits.size, matches = matches)
}

private fun findLinks(
    page: RenderedPage,
    request: BrowserFindRequest,
    base: BrowserFindResult,
    pageUrl: String?,
): BrowserFindResult {
    val origin = pageUrl?.let(::origin)
    val text = request.query
    val href = request.hrefContains
    val found =
        page.links.filter { link ->
            (text == null || link.text.contains(text, ignoreCase = true)) &&
                (href == null || link.href.contains(href, ignoreCase = true))
        }
    val links =
        found.take(request.limit).map {
            PageLink(it.text, it.href, it.offset, origin != null && origin(it.href) == origin)
        }
    return base.copy(total = found.size, links = links)
}

private fun origin(url: String): String? =
    try {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port =
            if (uri.port != -1) uri.port
            else
                when (scheme) {
                    "https" -> HTTPS_PORT
                    "http" -> HTTP_PORT
                    else -> -1
                }
        "$scheme://$host:$port"
    } catch (_: URISyntaxException) {
        null
    }

private const val MAX_READ_CHARS = 100_000
private const val MAX_FIND_RESULTS = 200
private const val MAX_CONTEXT_CHARS = 2_000
private const val MAX_SELECTOR = 1_000
private const val MAX_HEADING = 6
private const val BAD_GATEWAY = 502
private const val HEX = 16
private const val CRC_DIGITS = 8
private const val SPARSE_CHARS = 200
private const val MAX_COUNTED = 10_000
private const val HTTPS_PORT = 443
private const val HTTP_PORT = 80
