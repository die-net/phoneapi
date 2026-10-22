package net.die.phoneapi.tabs

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** The browser for REST and the raw CDP socket, which own nothing. */
    val shared: BrowserService by lazy { SharedBrowser(this, base) }

    /** The browser as [holder] sees it. */
    fun browserFor(holder: Holder): BrowserService =
        if (holder is Holder.Session) SessionBrowser(this, base, holder) else shared

    /** Waits as [holder] may run them: on its own tabs and unowned ones. */
    fun waitsFor(holder: Holder, base: WaitService): WaitService =
        if (holder is Holder.Session) {
            SessionWaits(base, browserFor(holder)) { access(holder, it) }
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
        return tab
    }

    internal suspend fun closed(id: String) {
        mutex.withLock {
            owners.remove(id)
            saveOpened()
        }
    }

    /**
     * Lets [holder] use [id]: its own tab, an unowned Chrome tab, which it then claims, or the
     * WebView of the app in front while it holds the screen. Another session's tab, like a missing
     * one, is not found.
     */
    internal suspend fun access(holder: Holder.Session, id: String) {
        val (socket, _) = parseBrowserTargetId(id)
        mutex.withLock {
            val owner = owners[id]
            when {
                owner?.holder == holder -> return
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
        if (owners.containsKey(id)) throw missing(id)
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
        mutex.withLock {
            for (tab in all) {
                val opener = tab.openerId?.let(owners::get)
                if (owners[tab.id] == null && opener != null) {
                    owners[tab.id] = Owner(opener.holder, opened = true)
                }
            }
            saveOpened()
        }
        for (tab in all) {
            val owner = owners[tab.id]
            val chrome = tab.socket == CHROME_SOCKET
            when {
                owner?.holder == holder -> out += tab.copy(owned = true)
                owner != null -> Unit
                chrome -> out += tab.copy(owned = false)
                holds && tab.packageName?.let { inFront(it) } == true ->
                    out += tab.copy(owned = false)
            }
        }
        return out
    }

    internal fun ownedByOthers(holder: Holder.Session, id: String): Boolean =
        owners[id]?.let { it.holder != holder } == true

    private fun saveOpened() {
        store.save(owners.filterValues { it.opened }.keys.toSet())
    }

    private suspend fun closeQuietly(id: String) {
        try {
            base.closeTab(id)
        } catch (_: ApiException) {
            // Already gone, or Chrome is not answering; the startup sweep tries again.
        }
    }

    private fun missing(id: String) = ApiException.notFound("No browser target $id")

    companion object {
        const val PER_SESSION = 4
        const val TOTAL = 8
    }
}
