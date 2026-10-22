package net.die.phoneapi.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.die.phoneapi.core.ApiException
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.FindKind
import net.die.phoneapi.model.READ_FORMAT_DOC
import net.die.phoneapi.model.READ_SCOPE_DOC
import net.die.phoneapi.model.READ_SELECTOR_DOC
import net.die.phoneapi.model.ReadFormat
import net.die.phoneapi.model.ReadScope
import net.die.phoneapi.model.SHOW_DOC
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.WaitCondition
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.schema.Doc
import net.die.phoneapi.server.ServerServices

@Serializable
internal data class BrowserReadArgs(
    @Doc("Browser target id. Pass this or url.") val target: String? = null,
    @Doc(
        "An http(s) URL to open in a new tab and read once it settles, instead of target. The result names the new target, so it can be read further and closed with browser_close."
    )
    val url: String? = null,
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc(
        "Return headings with their offsets, and counts of links, tables, and forms, instead of the content. Cheap."
    )
    val outline: Boolean = false,
    @Doc("Character offset to start at, such as nextOffset or an offset from browser_find.")
    val offset: Int = 0,
    @Doc("Most characters to return, 1 to 100000. Defaults to 8000.") val maxChars: Int = 8_000,
    @Doc(SHOW_DOC) val show: Boolean = false,
)

@Serializable
internal data class BrowserFindArgs(
    val target: String,
    @Doc("text (default) finds query in the content. links lists links, with their URLs.")
    val kind: FindKind = FindKind.TEXT,
    @Doc("For text, what to find, ignoring case. Required. For links, text the link must contain.")
    val query: String? = null,
    @Doc("Treat query as a regular expression, in Java syntax, ignoring case.")
    val regex: Boolean = false,
    @Doc("For links, a string the URL must contain, ignoring case.")
    val hrefContains: String? = null,
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc("Characters of context on each side of a text match, 0 to 2000. Defaults to 150.")
    val contextChars: Int = 150,
    @Doc("Most results, 1 to 200. Defaults to 10.") val limit: Int = 10,
)

internal suspend fun browserRead(
    services: ServerServices,
    scopes: Set<Scope>,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserReadArgs>()
    if ((args.target == null) == (args.url == null)) {
        throw ApiException.badRequest("Pass target or url, not both")
    }
    val read =
        BrowserReadRequest(
            scope = args.scope,
            selector = args.selector,
            format = args.format,
            outline = args.outline,
            offset = args.offset,
            maxChars = args.maxChars,
            show = args.show,
        )
    val opened = args.url?.let { url -> openAndSettle(services, scopes, url) }
    val target = opened?.target ?: args.target.orEmpty()
    val result = services.browser.read(target, read)
    val text =
        if (args.outline) outlineText(result, args.maxChars, args.show)
        else readText(result, args.show)
    opened?.let { "${it.line}\n$text" } ?: text
}

private data class Opened(val target: String, val line: String)

/**
 * Opens [url] in a new tab and waits up to [OPEN_SETTLE_MS] for it to load and settle. A settle
 * wait alone can match before the page has loaded, since requests from before the wait started are
 * not seen, so this also polls document.readyState.
 */
private suspend fun openAndSettle(
    services: ServerServices,
    scopes: Set<Scope>,
    url: String,
): Opened {
    val tab = services.browser.openTab(url)
    val deadline = System.currentTimeMillis() + OPEN_SETTLE_MS
    var settled = settle(services, scopes, tab.id, deadline)
    var loaded = loaded(services, tab.id)
    while (!loaded && System.currentTimeMillis() < deadline) {
        delay(LOAD_POLL_MS)
        loaded = loaded(services, tab.id)
        if (loaded) settled = settle(services, scopes, tab.id, deadline)
    }
    val line = buildString {
        append("# opened target=").append(tab.id)
        if (tab.tookScreen == true) append(" tookScreen")
        if (!loaded || !settled) {
            append(" (not ").append(if (loaded) "settled" else "loaded")
            append(" after ").append(OPEN_SETTLE_MS / MS_PER_S).append(" s)")
        }
        append("; close it with browser_close when done")
    }
    return Opened(tab.id, line)
}

/** Waits for the tab to settle, until [deadline]. True when it did. */
private suspend fun settle(
    services: ServerServices,
    scopes: Set<Scope>,
    target: String,
    deadline: Long,
): Boolean {
    val left = deadline - System.currentTimeMillis()
    if (left <= 0) return false
    val wait =
        services.waits.wait(
            WaitRequest(
                all = listOf(WaitCondition.BrowserSettled(target = target)),
                timeoutMs = left,
            ),
            scopes,
        )
    return wait.matched
}

private suspend fun loaded(services: ServerServices, target: String): Boolean {
    val state = services.browser.evaluate(target, EvalRequest("document.readyState")).value
    return (state as? JsonPrimitive)?.contentOrNull == "complete"
}

internal suspend fun browserFind(
    services: ServerServices,
    request: CallToolRequest,
): CallToolResult = runTool {
    val args = request.args<BrowserFindArgs>()
    val result =
        services.browser.find(
            args.target,
            BrowserFindRequest(
                kind = args.kind,
                query = args.query,
                regex = args.regex,
                hrefContains = args.hrefContains,
                scope = args.scope,
                selector = args.selector,
                format = args.format,
                contextChars = args.contextChars,
                limit = args.limit,
            ),
        )
    if (args.kind == FindKind.LINKS) linksText(result) else matchesText(result)
}

/** The page line every read and find result starts with. */
private fun pageLine(title: String?, url: String?, extra: String = ""): String = buildString {
    append("#")
    title?.let { append(" title=").append(quoted(it)) }
    url?.let { append(" url=").append(it) }
    append(extra)
}

private fun stateNotes(result: BrowserReadResult, show: Boolean): List<String> = buildList {
    result.blocked?.let { add("# blocked: $it") }
    if (result.sparse)
        add("# sparse: under 200 characters of visible text; it may still be loading")
    addAll(drawnNotes(result.shown, result.pending, result.sparse, show, "read"))
    val readyState = result.readyState
    if (readyState != null && readyState != "complete") {
        add("# readyState=$readyState: the page is still loading")
    }
    if (result.clipped) add("# clipped: the page has more text than reading takes")
}

/**
 * Notes on whether a tab was drawn. A hidden tab can be missing content that loads when drawn, so
 * when the page shows signs of that, the note says how to bring it forward. [again] names the tool
 * to call again.
 */
internal fun drawnNotes(
    shown: Boolean?,
    pending: List<String>,
    sparse: Boolean,
    show: Boolean,
    again: String,
): List<String> = buildList {
    if (pending.isNotEmpty()) add("# pending: " + pending.joinToString(", "))
    if (shown != false) return@buildList
    when {
        show ->
            add(
                "# still hidden after show: the tab did not come on screen; the phone may be locked"
            )
        sparse || pending.isNotEmpty() ->
            add(
                "# hidden: this tab is not on screen, so content that loads when drawn may be " +
                    "missing; $again again with show true to bring it forward"
            )
        else -> add("# hidden: this tab is not on screen")
    }
}

internal fun readText(result: BrowserReadResult, show: Boolean = false): String {
    val extra = buildString {
        result.published?.let { append(" published=").append(it) }
        result.status?.let { append(" status=").append(it) }
    }
    val end = result.offset + result.returnedChars
    val range = buildString {
        append("# ").append(result.extracted).append(" content, chars ")
        append(result.offset).append('-').append(end).append(" of ").append(result.totalChars)
        append(", version ").append(result.version)
        result.nextOffset?.let { append("; nextOffset=").append(it) }
            ?: append(if (result.totalChars == 0) "; empty" else "; end")
    }
    val lines = listOf(pageLine(result.title, result.url, extra)) + stateNotes(result, show) + range
    return lines.joinToString("\n") + "\n\n" + result.content.orEmpty()
}

internal fun outlineText(result: BrowserReadResult, maxChars: Int, show: Boolean = false): String {
    val headings = result.headings.orEmpty()
    val lines = headings.map { "@${it.offset} " + "#".repeat(it.level) + " " + it.text }
    var chars = 0
    val kept = lines.takeWhile { line ->
        chars += line.length + 1
        chars <= maxChars
    }
    val summary = buildString {
        append("# ").append(result.extracted).append(" content, ").append(result.totalChars)
        append(" chars, version ").append(result.version).append("; ")
        append(headings.size).append(" headings, @ is each one's offset. On the page: ")
        append(result.links).append(" links, ").append(result.tables).append(" tables, ")
        append(result.forms).append(" forms")
    }
    val notes = buildList {
        addAll(stateNotes(result, show))
        add(summary)
        if (kept.size < lines.size)
            add("# showing ${kept.size} of ${lines.size} headings; raise maxChars for more")
    }
    return (listOf(pageLine(result.title, result.url)) + notes + kept).joinToString("\n")
}

internal fun matchesText(result: BrowserFindResult): String {
    val matches = result.matches.orEmpty()
    val summary =
        "# ${result.total} matches in ${result.extracted} content (${result.totalChars} chars, " +
            "version ${result.version}); showing ${matches.size}, @ is each one's offset"
    val lines = matches.map { match ->
        buildString {
            append('@').append(match.offset).append(' ')
            match.heading?.let { append("under ").append(quoted(it)).append(": ") }
            append('…').append(oneLine(match.before)).append('«').append(oneLine(match.match))
            append('»').append(oneLine(match.after)).append('…')
        }
    }
    return (listOf(pageLine(result.title, result.url), summary) + lines).joinToString("\n")
}

internal fun linksText(result: BrowserFindResult): String {
    val links = result.links.orEmpty()
    val summary =
        "# ${result.total} links in ${result.extracted} content (version ${result.version}); " +
            "showing ${links.size}, @ is where each one's text starts"
    val lines = links.map {
        "@${it.offset} ${quoted(it.text)} ${it.href}" + if (it.sameOrigin) " same-origin" else ""
    }
    return (listOf(pageLine(result.title, result.url), summary) + lines).joinToString("\n")
}

private fun oneLine(text: String): String = text.replace(WHITESPACE, " ")

private fun quoted(text: String): String =
    "\"" + oneLine(text).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private val WHITESPACE = Regex("\\s+")
private const val OPEN_SETTLE_MS = 15_000L
private const val MS_PER_S = 1_000
private const val LOAD_POLL_MS = 250L
