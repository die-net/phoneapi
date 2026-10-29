package net.die.phoneapi.browser

import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.ReadScope

/** What [PageReader]'s evaluate gives back: the result, and whether Chrome had unloaded the tab. */
internal data class Evaluated(val result: EvalResult, val reloaded: Boolean)

/** A read's result, with the page's `document.visibilityState`, which says whether it was drawn. */
internal data class PageRead(val result: BrowserReadResult, val visibility: String?)

/**
 * Reads and searches a tab's text or data with the reading scripts. [pageScript] is Readability.js,
 * page-pending.js, page-data.js, then page-read.js; [dataScript] is page-data.js. [evaluate] runs
 * an expression in an isolated world on the tab with the given id.
 */
internal class PageReader(
    private val pageScript: suspend () -> String,
    private val dataScript: suspend () -> String,
    private val evaluate: suspend (id: String, expression: String) -> Evaluated,
) {
    @Volatile private var pageSource: String? = null
    @Volatile private var dataSource: String? = null

    suspend fun read(id: String, request: BrowserReadRequest): PageRead {
        if (request.scope == ReadScope.DATA) {
            val data = data(id, wantedBlobs(request.paths.ifEmpty { listOfNotNull(request.path) }))
            return PageRead(readData(data, request).copy(reloaded = data.reloaded), data.visibility)
        }
        val extract = page(id, request.scope, request.selector)
        return PageRead(
            readPage(extract, request).copy(reloaded = extract.reloaded),
            extract.visibility,
        )
    }

    suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult {
        if (request.scope == ReadScope.DATA) {
            val data = data(id, wantedBlobs(listOfNotNull(request.path)))
            return findData(data, request).copy(reloaded = data.reloaded)
        }
        return findWithFallback(request) { scope -> page(id, scope, request.selector) }
    }

    private suspend fun page(id: String, scope: ReadScope, selector: String?): PageExtract {
        val source = pageSource ?: pageScript().also { pageSource = it }
        val (result, reloaded) = evaluate(id, readScript(source, scope, selector))
        return decodeExtract(result, selector).copy(reloaded = reloaded)
    }

    /** The page's data blobs, with the text of those [want] names, or of all when it is null. */
    private suspend fun data(id: String, want: List<String>?): DataExtract {
        val source = dataSource ?: dataScript().also { dataSource = it }
        val (result, reloaded) = evaluate(id, dataExpression(source, want))
        return decodeData(result).copy(reloaded = reloaded)
    }
}
