package net.die.phoneapi.tabs

import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.BrowserFindRequest
import net.die.phoneapi.model.BrowserFindResult
import net.die.phoneapi.model.BrowserGestureRequest
import net.die.phoneapi.model.BrowserKeyRequest
import net.die.phoneapi.model.BrowserReadRequest
import net.die.phoneapi.model.BrowserReadResult
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserSnapshotOptions
import net.die.phoneapi.model.BrowserSwipeRequest
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.BrowserTextRequest
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult

/** The browser as one MCP session sees it: its own tabs and unowned ones, as [Tabs] rules. */
internal class SessionBrowser(
    private val tabs: Tabs,
    private val base: BrowserService,
    private val holder: Holder.Session,
) : BrowserService {
    override suspend fun targets(): List<BrowserTarget> = tabs.visible(holder, base.targets())

    override suspend fun openTab(url: String): BrowserTarget = tabs.opened(holder, url)

    override suspend fun navigate(id: String, url: String): BrowserTarget =
        mine(id) { base.navigate(id, url) }

    override suspend fun closeTab(id: String): ActionResult =
        mine(id) { base.closeTab(id) }.also { tabs.closed(id) }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        mine(id) { base.snapshot(id, options) }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult =
        mine(id) { base.read(id, request) }

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult =
        mine(id) { base.find(id, request) }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult =
        mine(id) { base.tap(id, request) }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        mine(id) { base.swipe(id, request) }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        mine(id) { base.gesture(id, request) }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult =
        mine(id) { base.key(id, request) }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult =
        mine(id) { base.text(id, request) }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult =
        mine(id) { base.evaluate(id, request) }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult =
        mine(id) { base.console(id, request) }

    private suspend fun <T> mine(id: String, block: suspend () -> T): T {
        tabs.access(holder, id)
        return block()
    }
}

/** The browser for REST and the raw CDP socket: every tab no live session owns. */
internal class SharedBrowser(private val tabs: Tabs, private val base: BrowserService) :
    BrowserService {
    override suspend fun targets(): List<BrowserTarget> = base.targets()

    override suspend fun openTab(url: String): BrowserTarget = base.openTab(url)

    override suspend fun navigate(id: String, url: String): BrowserTarget =
        free(id) { base.navigate(id, url) }

    override suspend fun closeTab(id: String): ActionResult = free(id) { base.closeTab(id) }

    override suspend fun snapshot(id: String, options: BrowserSnapshotOptions): BrowserSnapshot =
        free(id) { base.snapshot(id, options) }

    override suspend fun read(id: String, request: BrowserReadRequest): BrowserReadResult =
        free(id) { base.read(id, request) }

    override suspend fun find(id: String, request: BrowserFindRequest): BrowserFindResult =
        free(id) { base.find(id, request) }

    override suspend fun tap(id: String, request: BrowserTapRequest): ActionResult =
        free(id) { base.tap(id, request) }

    override suspend fun swipe(id: String, request: BrowserSwipeRequest): ActionResult =
        free(id) { base.swipe(id, request) }

    override suspend fun gesture(id: String, request: BrowserGestureRequest): ActionResult =
        free(id) { base.gesture(id, request) }

    override suspend fun key(id: String, request: BrowserKeyRequest): ActionResult =
        free(id) { base.key(id, request) }

    override suspend fun text(id: String, request: BrowserTextRequest): ActionResult =
        free(id) { base.text(id, request) }

    override suspend fun evaluate(id: String, request: EvalRequest): EvalResult =
        free(id) { base.evaluate(id, request) }

    override suspend fun console(id: String, request: ConsoleRequest): ConsoleResult =
        free(id) { base.console(id, request) }

    private suspend fun <T> free(id: String, block: suspend () -> T): T {
        tabs.accessShared(id)
        return block()
    }
}
