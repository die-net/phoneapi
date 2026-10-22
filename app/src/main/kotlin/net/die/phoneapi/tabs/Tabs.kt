package net.die.phoneapi.tabs

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.die.phoneapi.browser.CHROME_PACKAGE
import net.die.phoneapi.browser.CHROME_SOCKET
import net.die.phoneapi.browser.parseBrowserTargetId
import net.die.phoneapi.core.BrowserService
import net.die.phoneapi.core.WaitService
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.lease.ScreenLease
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.BrowserTarget

/** Where the ids of tabs that sessions opened are kept, so a restart can close leftovers. */
interface TabStore {
    fun load(): Set<String>

    fun save(ids: Set<String>)
}

/**
 * Who owns which browser tab, as docs/sharing.md describes. A session owns the tabs it opens,
 * popups those tabs open, and unowned tabs it claims by using them. Other sessions cannot see or
 * touch them. When a session ends, the tabs it opened close and those it claimed go back to being
 * unowned. REST and the raw CDP socket can use any tab a live session does not own.
 *
 * Whenever Chrome is in front, it shows the screen holder's tab or an `about:blank` placeholder no
 * one owns, never another session's page.
 */
class Tabs(
    private val base: BrowserService,
    private val lease: ScreenLease,
    private val inFront: suspend (String) -> Boolean,
    private val store: TabStore,
    private val perSession: Int = PER_SESSION,
    private val total: Int = TOTAL,
) {
    private data class Owner(val holder: Holder.Session, val opened: Boolean)

    private val owners = ConcurrentHashMap<String, Owner>()
    private val mutex = Mutex()
    private val placeholderMutex = Mutex()

    /** The tab Chrome shows when the screen holder has none forward. */
    @Volatile private var placeholder: String? = null

    /** The tab each session last opened or brought forward. */
    private val fronts = ConcurrentHashMap<Holder.Session, String>()

    /** The browser for REST and the raw CDP socket, which own nothing. */
    val shared: BrowserService by lazy { SharedBrowser(this, base) }

    /** Chrome's tabs for REST, without the placeholder. */
    internal suspend fun sharedTargets(): List<BrowserTarget> =
        base.targets().filter { it.id != placeholder }

    /**
     * The browser as [holder] sees it. A tab that belongs to a session [related] to it is still
     * refused, but the error says whose it is, since the caller can use it through that session.
     */
    fun browserFor(
        holder: Holder,
        related: (Holder.Session) -> Boolean = { false },
    ): BrowserService =
        if (holder is Holder.Session) SessionBrowser(this, base, holder, related) else shared

    /** Waits as [holder] may run them: on its own tabs and unowned ones. */
    fun waitsFor(
        holder: Holder,
        base: WaitService,
        related: (Holder.Session) -> Boolean = { false },
    ): WaitService =
        if (holder is Holder.Session) {
            SessionWaits(base, browserFor(holder, related)) { access(holder, it, related) }
        } else {
            sharedWaits(base)
        }

    /** Waits for REST: on any tab no live session owns. */
    fun sharedWaits(base: WaitService): WaitService = SessionWaits(base, null) { accessShared(it) }

    /** How many tabs [holder] owns, opened or claimed. */
    fun count(holder: Holder.Session): Int = owners.values.count { it.holder == holder }

    /** Closes the tabs [holder] opened and gives back those it claimed. */
    suspend fun end(holder: Holder.Session) {
        val mine = mutex.withLock {
            val ids = owners.filterValues { it.holder == holder }
            ids.keys.forEach(owners::remove)
            fronts.remove(holder)
            saveOpened()
            ids
        }
        for ((id, owner) in mine) {
            if (owner.opened) closeQuietly(id)
        }
    }

    /** Closes tabs that sessions opened before the app last stopped. */
    suspend fun sweep() {
        val left = store.load()
        if (left.isEmpty()) return
        val open =
            try {
                base.targets().map { it.id }.toSet()
            } catch (_: ApiException) {
                // Chrome is not reachable yet; keep the list for the next start.
                return
            }
        for (id in left.filter { it in open }) closeQuietly(id)
        mutex.withLock { saveOpened() }
    }

    internal suspend fun opened(holder: Holder.Session, url: String): BrowserTarget {
        mutex.withLock {
            val opened = owners.values.filter { it.opened }
            if (opened.count { it.holder == holder } >= perSession || opened.size >= total) {
                throw ApiException(
                    409,
                    "tab_limit",
                    "Too many tabs are open: $perSession per session and $total in all. Close one " +
                        "you are done with first.",
                )
            }
        }
        val tab = base.openTab(url)
        mutex.withLock {
            owners[tab.id] = Owner(holder, opened = true)
            saveOpened()
        }
        if (lease.holds(holder)) {
            fronts[holder] = tab.id
            return tab
        }
        // Chrome switches to a new tab while it is in front, which must not show this session's
        // page to whoever holds the screen.
        if (!putBack()) {
            closeQuietly(tab.id)
            closed(tab.id)
            throw ApiException(
                409,
                "took_screen",
                "Chrome showed the new tab on screen and would not switch back, so PhoneAPI " +
                    "closed it. Try again once Chrome is in the background.",
            )
        }
        return tab.copy(tookScreen = false)
    }

    internal suspend fun closed(id: String) {
        mutex.withLock {
            owners.remove(id)
            fronts.values.remove(id)
            saveOpened()
        }
    }

    /** Remembers that [holder] brought [id] forward, so Chrome goes back to it. */
    internal fun broughtForward(holder: Holder.Session, id: String) {
        if (lease.holds(holder)) fronts[holder] = id
    }

    /**
     * After a call by [holder] that a page could answer by opening a popup: if [holder] does not
     * hold the screen and Chrome is in front, Chrome goes back to the screen holder's tab.
     */
    internal suspend fun afterInput(holder: Holder.Session) {
        if (lease.holds(holder) || !chromeInFront()) return
        adoptPopups(base.targets())
        if (!putBack()) {
            throw ApiException(
                409,
                "took_screen",
                "Chrome showed a tab this session's page opened and would not switch back. " +
                    "Close the popup.",
            )
        }
    }

    /**
     * Runs [holder]'s screen call [block], making sure Chrome shows none of another session's tabs
     * while Chrome is in front: before the call, and after it when Chrome came forward during it.
     */
    suspend fun <T> screenCall(holder: Holder, block: suspend () -> T): T {
        // With no other session's tabs, there is nothing Chrome could show by mistake.
        if (owners.values.all { it.holder == holder }) return block()
        val before = chromeInFront()
        if (before) bestEffort { showFor(holder) }
        val value = block()
        if (!before && chromeInFront()) bestEffort { showFor(holder) }
        return value
    }

    private suspend fun chromeInFront(): Boolean =
        try {
            inFront(CHROME_PACKAGE)
        } catch (_: ApiException) {
            false
        }

    /** Fixing Chrome's selection must not fail the holder's own call. */
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: ApiException) {
            // Chrome is not answering; the next screen call tries again.
        }
    }

    /** For cleanup: shows the placeholder if Chrome is in front, so it is blank next time. */
    suspend fun showPlaceholder() {
        if (!inFront(CHROME_PACKAGE)) return
        base.activate(placeholderTab())
    }

    /** Between screen calls, puts Chrome back on the screen holder's tab. False if it would not. */
    private suspend fun putBack(): Boolean =
        try {
            lease.betweenCalls { showFor(lease.state.value.holder) }
        } catch (_: ApiException) {
            false
        }

    /**
     * With Chrome in front, selects [holder]'s tab, or the placeholder, if Chrome shows another
     * session's. Selecting a tab can bring Chrome forward, so this does nothing while it is hidden.
     * True when Chrome shows nothing it should not.
     */
    private suspend fun showFor(holder: Holder?): Boolean {
        if (!inFront(CHROME_PACKAGE)) return true
        val front = (holder as? Holder.Session)?.let { fronts[it] }
        if (front != null && base.showing(front)) return true
        // A popup a page opened on its own, since the last listing, is its opener's too.
        adoptPopups(base.targets())
        val others = owners.filterValues { it.holder != holder }.keys
        if (others.none { base.showing(it) }) return true
        val view = front ?: placeholderTab()
        base.activate(view)
        delay(SELECT_MS)
        return base.showing(view)
    }

    /** The placeholder, opened again if it was closed. */
    private suspend fun placeholderTab(): String = placeholderMutex.withLock {
        val id = placeholder
        if (id != null && base.targets().any { it.id == id }) return@withLock id
        val tab = base.openTab(BLANK)
        placeholder = tab.id
        mutex.withLock { saveOpened() }
        tab.id
    }

    /**
     * Lets [holder] use [id]: its own tab, an unowned Chrome tab, which it then claims, or the
     * WebView of the app in front while it holds the screen. Another session's tab, like a missing
     * one, is not found, unless it belongs to a session [related] to [holder].
     */
    internal suspend fun access(
        holder: Holder.Session,
        id: String,
        related: (Holder.Session) -> Boolean = { false },
    ) {
        val (socket, _) = parseBrowserTargetId(id)
        if (id == placeholder) throw missing(id)
        mutex.withLock {
            val owner = owners[id]
            when {
                owner?.holder == holder -> return
                owner != null && related(owner.holder) -> throw elsewhere(id)
                owner != null -> throw missing(id)
                socket == CHROME_SOCKET -> {
                    owners[id] = Owner(holder, opened = false)
                    return
                }
            }
        }
        val pkg = base.targets().firstOrNull { it.id == id }?.packageName
        if (!lease.holds(holder) || pkg == null || !inFront(pkg)) throw missing(id)
    }

    /** REST and raw CDP: any tab a live session does not own. */
    fun accessShared(id: String) {
        if (owners.containsKey(id) || id == placeholder) throw missing(id)
    }

    /**
     * What [holder] may list: its own tabs, unowned Chrome tabs, and the screen holder's front-app
     * WebViews. Popups of its tabs become its own here.
     */
    internal suspend fun visible(
        holder: Holder.Session,
        all: List<BrowserTarget>,
    ): List<BrowserTarget> {
        val holds = lease.holds(holder)
        val out = ArrayList<BrowserTarget>()
        adoptPopups(all)
        for (tab in all) {
            val owner = owners[tab.id]
            val chrome = tab.socket == CHROME_SOCKET
            when {
                tab.id == placeholder -> Unit
                owner?.holder == holder -> out += tab.copy(owned = true)
                owner != null -> Unit
                chrome -> out += tab.copy(owned = false)
                holds && tab.packageName?.let { inFront(it) } == true ->
                    out += tab.copy(owned = false)
            }
        }
        return out
    }

    /** Gives each unowned popup in [all] to the owner of the tab that opened it. */
    private suspend fun adoptPopups(all: List<BrowserTarget>) {
        mutex.withLock {
            for (tab in all) {
                val opener = tab.openerId?.let(owners::get)
                if (owners[tab.id] == null && opener != null) {
                    owners[tab.id] = Owner(opener.holder, opened = true)
                }
            }
            saveOpened()
        }
    }

    internal fun ownedByOthers(holder: Holder.Session, id: String): Boolean =
        owners[id]?.let { it.holder != holder } == true

    private fun saveOpened() {
        store.save(owners.filterValues { it.opened }.keys + listOfNotNull(placeholder))
    }

    private suspend fun closeQuietly(id: String) {
        try {
            base.closeTab(id)
        } catch (_: ApiException) {
            // Already gone, or Chrome is not answering; the startup sweep tries again.
        }
    }

    private fun missing(id: String) = ApiException.notFound("No browser target $id")

    private fun elsewhere(id: String) =
        ApiException.notFound(
            "Browser target $id belongs to another session in this conversation: your own, or " +
                "one from session_start. Use it with the session that opened it: leave session " +
                "out for your own tabs, or pass the id from session_start."
        )

    companion object {
        const val PER_SESSION = 4
        const val TOTAL = 8
        private const val BLANK = "about:blank"

        /** How long Chrome takes to switch tabs before this checks. */
        private const val SELECT_MS = 200L
    }
}
