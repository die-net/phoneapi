package net.die.phoneapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import net.die.phoneapi.model.schema.Doc

/** One Chrome tab or debuggable WebView, addressed by [id] on later calls. */
@Serializable
public data class BrowserTarget(
    val id: String,
    val type: String,
    val title: String? = null,
    val url: String? = null,
    /** Abstract-namespace socket this target was listed from. */
    val socket: String,
    val pid: Int? = null,
    @SerialName("package") val packageName: String? = null,
    @Doc(
        "Set when a tab is opened. True when Chrome was on screen, so it switched to the new tab and the call took the screen. False when the tab opened in the background."
    )
    val tookScreen: Boolean? = null,
    @Doc("The tab that opened this one, as for a popup or a link opened in a new tab.")
    val openerId: String? = null,
    @Doc(
        "For an MCP session: true for its own tabs, false for unowned ones, which it claims by using them. Other sessions' tabs are not listed."
    )
    val owned: Boolean? = null,
)

@Serializable public data class OpenTabRequest(val url: String)

@Serializable public data class NavigateRequest(val url: String)

/** How browser input is delivered. [AUTO] is the default. */
@Serializable
public enum class BrowserInput {
    /**
     * [TOUCH] for a caller that holds the screen. Otherwise [CDP_MOUSE] for a tap and [CDP] for
     * keys and text, so the call does not take a free screen, and [TOUCH] for a swipe or gesture,
     * which needs it.
     */
    @SerialName("auto") AUTO,
    /** Bring the tab forward and inject a hardware event. */
    @SerialName("touch") TOUCH,
    /**
     * Touch events over DevTools, without bringing the tab forward. Chrome holds touch input for
     * any tab it is not showing, so this needs the tab showing in Chrome, on screen. Keys and text
     * reach a background tab.
     */
    @SerialName("cdp") CDP,
    /**
     * A mouse click over DevTools. Reaches a background tab, even with Chrome hidden and the screen
     * off, but the page sees a mouse rather than a finger. Taps only.
     */
    @SerialName("cdp-mouse") CDP_MOUSE;

    /** What this input means for [kind] of input, for a caller that [holdsScreen] or not. */
    public fun resolved(kind: BrowserInputKind, holdsScreen: Boolean): BrowserInput =
        when {
            this != AUTO -> this
            holdsScreen || kind == BrowserInputKind.POINTER -> TOUCH
            kind == BrowserInputKind.TAP -> CDP_MOUSE
            else -> CDP
        }
}

/** The kinds of browser input, which [BrowserInput.AUTO] resolves differently. */
public enum class BrowserInputKind {
    TAP,
    /** Swipes and gestures, which only touch can deliver to a background tab. */
    POINTER,
    KEYS,
}

public const val BROWSER_INPUT_DOC: String =
    "touch brings the tab to the front and uses the screen. cdp sends touch events over DevTools without bringing the tab forward, but only to the tab Chrome is showing, on screen. Keys and text with cdp reach a background tab. cdp-mouse taps with a mouse click, which reaches a background tab even with Chrome hidden and the screen off, but the page sees a mouse, not a finger. auto (default) is touch for a caller that holds the screen, and otherwise cdp-mouse for a tap and cdp for keys and text, so it does not take a free screen; a swipe or gesture uses touch."

public const val BROWSER_WAKE_DOC: String =
    "Turn the screen on first when input is touch. cdp and cdp-mouse never wake it. Defaults to true."

/**
 * Tap a browser target. Pass a snapshot [ref] (the id in square brackets) or a CSS [selector], not
 * both.
 */
@Serializable
public data class BrowserTapRequest(
    val ref: String? = null,
    val selector: String? = null,
    @Doc(
        "Jitter the point, timing, and finger contact. Defaults to true. A CDP tap matches a hardware tap."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
    @Doc(TAP_WAIT_DOC) val waitMs: Long = DEFAULT_TAP_WAIT_MS,
)

public const val TAP_WAIT_DOC: String =
    "How long to wait for selector to match an element with a box, in milliseconds, 0 to 30000. Defaults to 5000. The element is scrolled into view either way."

public const val DEFAULT_TAP_WAIT_MS: Long = 5_000

/**
 * Swipe a browser target. [from] and [to] are CSS viewport pixels. Pass those, or a [direction]
 * across the page or inside a [ref] or CSS [selector].
 */
@Serializable
public data class BrowserSwipeRequest(
    @Doc("Start, in CSS viewport pixels. Pass both from and to, or a direction.")
    val from: Point? = null,
    @Doc("End, in CSS viewport pixels. Pass both from and to, or a direction.")
    val to: Point? = null,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("up, down, left, or right. Use instead of from and to.")
    val direction: SwipeDirection? = null,
    @Doc("Fraction of the page or element to travel when using direction.")
    val distance: Float = 0.6f,
    @Doc("How long the swipe takes, in milliseconds.") val durationMs: Long = 300,
    @Doc("Keep velocity at release instead of stopping before the pointer lifts.")
    val fling: Boolean = true,
    @Doc(
        "Jitter the path, timing, and finger contact. Defaults to true. A CDP swipe matches a hardware swipe."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/**
 * One gesture on a browser target. Each pointer path is in CSS viewport pixels, with times relative
 * to the gesture start.
 */
@Serializable
public data class BrowserGestureRequest(
    val pointers: List<List<TimedPoint>>,
    @Doc(
        "Jitter the path and vary the finger contact. Defaults to true. A CDP gesture matches a hardware gesture."
    )
    val humanize: Boolean = true,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/** A key aimed at a browser target. Names match [KeyRequest]. */
@Serializable
public data class BrowserKeyRequest(
    val key: String,
    val longPress: Boolean = false,
    val metaState: Int = 0,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/**
 * Type into a browser target. [ref] or CSS [selector] focuses a field first. [mode] applies to
 * [BrowserInput.TOUCH]; [BrowserInput.CDP] inserts the text.
 */
@Serializable
public data class BrowserTextRequest(
    @Doc("Characters to type.") val text: String,
    val ref: String? = null,
    val selector: String? = null,
    @Doc("auto, keyboard, keyevent, or setText. Used when input is touch.")
    val mode: TextMode = TextMode.AUTO,
    @Doc("Clear the field before typing.") val clear: Boolean = false,
    @Doc("Press Enter afterwards.") val submit: Boolean = false,
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 140,
    @Doc(BROWSER_WAKE_DOC) val autoWake: Boolean = true,
    @Doc(BROWSER_INPUT_DOC) val input: BrowserInput = BrowserInput.AUTO,
)

/** Evaluate [expression] in an isolated world so the page's JavaScript is untouched. */
@Serializable
public data class EvalRequest(val expression: String, val awaitPromise: Boolean = false)

@Serializable
public data class EvalResult(
    val type: String? = null,
    val value: JsonElement? = null,
    val exception: String? = null,
)

/**
 * Collect console and browser log events for [timeoutMs]. Entries from before the call are not
 * replayed.
 */
@Serializable public data class ConsoleRequest(val timeoutMs: Long = 1_000)

@Serializable public data class ConsoleEntry(val level: String, val text: String)

@Serializable public data class ConsoleResult(val entries: List<ConsoleEntry> = emptyList())

/** Caps on a browser snapshot's outline. Lines left out are counted and reported as cut. */
@Serializable
public data class BrowserSnapshotOptions(
    @Doc("Most node lines to show, 1 to 5000. Defaults to 400.") val maxNodes: Int = 400,
    @Doc("Most characters of outline below the header, cut at a line.") val maxChars: Int? = null,
    @Doc(SHOW_DOC) val show: Boolean = false,
)

/** Compact accessibility tree of a browser target. */
@Serializable
public data class BrowserSnapshot(
    val id: String,
    val url: String? = null,
    val title: String? = null,
    val compact: String,
    @Doc(SHOWN_DOC) val shown: Boolean? = null,
    @Doc(PENDING_DOC) val pending: List<String> = emptyList(),
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
)

/** Which part of a page browser reads and finds work over. */
@Serializable
public enum class ReadScope {
    /** The main content, as Readability finds it, without navigation, footers, and ads. */
    @SerialName("article") ARTICLE,
    /** All visible text on the page. */
    @SerialName("page") PAGE,
}

@Serializable
public enum class ReadFormat {
    @SerialName("markdown") MARKDOWN,
    @SerialName("text") TEXT,
}

public const val READ_SCOPE_DOC: String =
    "article (default) is the main content, without navigation, footers, and ads, and falls back to page when no article is found. page is all visible text."

public const val READ_SELECTOR_DOC: String =
    "CSS selector for one element to read instead of scope, such as a table or an article body."

public const val SHOW_DOC: String =
    "Bring the tab on screen first, waking the phone, and wait until its text stops growing, so content that loads only when drawn can load. Takes the screen, like touch input."

public const val SHOWN_DOC: String =
    "Whether Chrome was drawing this tab: Chrome in front with this tab selected, on an awake, unlocked phone. A hidden tab can be missing content that loads when drawn. Absent when it could not be told."

public const val PENDING_DOC: String =
    "Signs the page is still waiting to load content: a busy region, loading placeholders, or a Load more control."

public const val RELOADED_DOC: String =
    "Chrome had unloaded this hidden tab to save memory, so PhoneAPI loaded it again first. Anything typed, scrolled, or opened on the page before is gone."

public const val READ_FORMAT_DOC: String =
    "markdown (default) or text. Offsets depend on the format, so use the same one for reading and finding."

/**
 * Read a browser target's text. Offsets count characters of the rendered content, so a
 * [BrowserFindResult] offset can be passed here with the same scope, selector, and format.
 */
@Serializable
public data class BrowserReadRequest(
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc(
        "Return headings with their offsets, and counts of links, tables, and forms, instead of the content."
    )
    val outline: Boolean = false,
    @Doc("Character offset to start at, such as nextOffset or a match's offset.")
    val offset: Int = 0,
    @Doc("Most characters to return, 1 to 100000. Defaults to 8000. Cut at a line or a space.")
    val maxChars: Int = 8_000,
    @Doc(SHOW_DOC) val show: Boolean = false,
)

/** A heading in rendered content, at the offset its line starts. */
@Serializable public data class PageHeading(val level: Int, val text: String, val offset: Int)

@Serializable
public data class BrowserReadResult(
    val title: String? = null,
    @Doc("The page URL now, after redirects.") val url: String? = null,
    @Doc("When the page says it was published, from its metadata.") val published: String? = null,
    @Doc("document.readyState: loading, interactive, or complete.") val readyState: String? = null,
    @Doc("HTTP status of the page's main document, when Chrome reports one.")
    val status: Int? = null,
    @Doc(
        "Why the page looks like a block rather than content: captcha, challenge page, or http 401, 403, or 429."
    )
    val blocked: String? = null,
    @Doc(
        "The page has under 200 characters of visible text, so it may still be loading or need scripts."
    )
    val sparse: Boolean = false,
    @Doc(PENDING_DOC) val pending: List<String> = emptyList(),
    @Doc(SHOWN_DOC) val shown: Boolean? = null,
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
    @Doc("What was read: article, page (also when no article was found), or selector.")
    val extracted: String,
    @Doc("Changes when the rendered content changes, so offsets from another call still apply.")
    val version: String,
    @Doc("Length of the whole rendered content.") val totalChars: Int,
    val offset: Int,
    val returnedChars: Int,
    @Doc("Where the next read starts. Absent at the end.") val nextOffset: Int? = null,
    @Doc("True when content before offset or after this slice was not returned.")
    val truncated: Boolean,
    @Doc("The page has more text than reading takes, so the end is missing.")
    val clipped: Boolean = false,
    val content: String? = null,
    @Doc("Set when outline was requested.") val headings: List<PageHeading>? = null,
    @Doc("Links on the whole page.") val links: Int = 0,
    @Doc("Tables on the whole page.") val tables: Int = 0,
    @Doc("Forms on the whole page.") val forms: Int = 0,
)

/** What [BrowserFindRequest] looks for. */
@Serializable
public enum class FindKind {
    @SerialName("text") TEXT,
    @SerialName("links") LINKS,
}

/**
 * Search a browser target's rendered content. Offsets match [BrowserReadRequest] with the same
 * scope, selector, and format.
 */
@Serializable
public data class BrowserFindRequest(
    @Doc("text (default) finds query in the content. links lists links in the content.")
    val kind: FindKind = FindKind.TEXT,
    @Doc(FIND_QUERY_DOC) val query: String? = null,
    @Doc(FIND_QUERIES_DOC) val queries: List<String> = emptyList(),
    @Doc("Treat query or queries as regular expressions, in Java syntax, ignoring case.")
    val regex: Boolean = false,
    @Doc("For links, a string the URL must contain, ignoring case.")
    val hrefContains: String? = null,
    @Doc(READ_SCOPE_DOC) val scope: ReadScope = ReadScope.ARTICLE,
    @Doc(READ_SELECTOR_DOC) val selector: String? = null,
    @Doc(READ_FORMAT_DOC) val format: ReadFormat = ReadFormat.MARKDOWN,
    @Doc("Characters of context on each side of a text match, 0 to 2000. Defaults to 150.")
    val contextChars: Int = 150,
    @Doc("Most results, 1 to 200, for each query when there are several. Defaults to 10.")
    val limit: Int = 10,
)

public const val FIND_QUERY_DOC: String =
    "For text, what to find: a plain substring that ignores case, so gate also matches navigate; use regex with \\bgate\\b for whole words. text needs query or queries. For links, text the link must contain."

public const val FIND_QUERIES_DOC: String =
    """For text, up to 20 things to find in one call, each searched on its own, with its own matches, and a plain no-matches line when it is not there. Give the likely wordings and synonyms of what you want, such as ["gate", "departure gate", "boarding"], rather than one find per guess; a miss costs almost nothing. Each is a plain substring that ignores case, so capitalizations need no entries of their own. Use query or queries, not both."""

/** The most queries one find takes. */
public const val MAX_FIND_QUERIES: Int = 20

@Serializable
public data class QueryMatches(
    val query: String,
    @Doc("How many matches there are in all. Only the first limit are returned.") val total: Int,
    val matches: List<TextMatch> = emptyList(),
)

@Serializable
public data class TextMatch(
    val offset: Int,
    @Doc("The nearest heading before the match.") val heading: String? = null,
    val before: String,
    val match: String,
    val after: String,
)

@Serializable
public data class PageLink(
    val text: String,
    val href: String,
    @Doc("Where the link text starts in the rendered content.") val offset: Int,
    @Doc("Same scheme, host, and port as the page.") val sameOrigin: Boolean,
)

@Serializable
public data class BrowserFindResult(
    val title: String? = null,
    val url: String? = null,
    val extracted: String,
    val version: String,
    val totalChars: Int,
    @Doc("How many results there are in all. Only the first limit are returned.") val total: Int,
    val matches: List<TextMatch>? = null,
    @Doc("With queries, each one's matches, in the order asked. total is their sum.")
    val queries: List<QueryMatches>? = null,
    val links: List<PageLink>? = null,
    @Doc(
        "Links left out of links and total because an earlier link has the same URL once tracking parameters are dropped."
    )
    val repeats: Int = 0,
    @Doc(RELOADED_DOC) val reloaded: Boolean = false,
)
