package net.die.phoneapi.core

import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.BrowserSnapshot
import net.die.phoneapi.model.BrowserTapRequest
import net.die.phoneapi.model.BrowserTarget
import net.die.phoneapi.model.ConsoleRequest
import net.die.phoneapi.model.ConsoleResult
import net.die.phoneapi.model.EvalRequest
import net.die.phoneapi.model.EvalResult
import net.die.phoneapi.model.FindRequest
import net.die.phoneapi.model.FindResult
import net.die.phoneapi.model.GestureRequest
import net.die.phoneapi.model.ImeShowRequest
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.KeyRequest
import net.die.phoneapi.model.LaunchRequest
import net.die.phoneapi.model.NodeActionRequest
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.SwipeRequest
import net.die.phoneapi.model.TapRequest
import net.die.phoneapi.model.TextRequest
import net.die.phoneapi.model.UiSnapshot
import net.die.phoneapi.model.UnlockRequest
import net.die.phoneapi.model.WaitRequest
import net.die.phoneapi.model.WaitResult

// Shared by the REST routes and the MCP tools. Implementations throw ApiException for
// client-visible failures.

typealias SnapshotOptions = net.die.phoneapi.model.SnapshotOptions

interface UiService {
    suspend fun snapshot(options: SnapshotOptions): UiSnapshot

    suspend fun find(request: FindRequest): FindResult

    suspend fun act(ref: String, request: NodeActionRequest): ActionResult
}

interface InputService {
    suspend fun tap(request: TapRequest): ActionResult

    suspend fun swipe(request: SwipeRequest): ActionResult

    suspend fun gesture(request: GestureRequest): ActionResult

    suspend fun key(request: KeyRequest): ActionResult

    suspend fun text(request: TextRequest): ActionResult

    suspend fun hideIme(): ActionResult

    suspend fun showIme(request: ImeShowRequest): ActionResult
}

interface PowerService {
    suspend fun wake(): ActionResult

    suspend fun unlock(request: UnlockRequest): ActionResult

    suspend fun lock(): ActionResult

    /**
     * Called before every action. Wakes (and unlocks, if possible) when [autoWake] is set and the
     * screen is off, extends the awake lease, and returns whether the device was woken. Throws `409
     * device_locked` when the device stays locked.
     */
    suspend fun prepareForAction(autoWake: Boolean, allowLocked: Boolean = false): Boolean
}

interface AppsService {
    suspend fun list(launchableOnly: Boolean): List<AppInfo>

    suspend fun launch(packageName: String, request: LaunchRequest): ActionResult

    suspend fun stop(packageName: String): ActionResult

    suspend fun clear(packageName: String): ActionResult

    suspend fun intent(request: IntentRequest): ActionResult
}

interface WaitService {
    /**
     * Blocks until [request] matches. A `browser.*` condition is 403 when [scopes] lacks
     * [Scope.BROWSER], before a DevTools session is opened.
     */
    suspend fun wait(request: WaitRequest, scopes: Set<Scope>): WaitResult
}

interface BrowserService {
    suspend fun targets(): List<BrowserTarget>

    suspend fun openTab(url: String): BrowserTarget

    suspend fun navigate(id: String, url: String): BrowserTarget

    suspend fun snapshot(id: String): BrowserSnapshot

    suspend fun tap(id: String, request: BrowserTapRequest): ActionResult

    suspend fun evaluate(id: String, request: EvalRequest): EvalResult

    suspend fun console(id: String, request: ConsoleRequest): ConsoleResult
}
