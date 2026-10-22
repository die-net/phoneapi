package net.die.phoneapi.mcp

import net.die.phoneapi.lease.Holder

/**
 * Sessions as one MCP session's tools see them. `session_start` makes a session under it, for a
 * subagent or a parallel task, with its own turn at the screen and its own tabs; a call names it
 * with the `session` argument. Such sessions end with `session_end`, by idling out, or with the
 * session that started them.
 */
interface SessionControl {
    /** Starts a session under this one and returns its id. */
    fun start(): String

    /** Ends a session this one started. False when there is no such session. */
    suspend fun end(id: String): Boolean

    /** The holder [id] names, if it is this session or a live one it started. */
    fun resolve(id: String): Holder.Session?

    /** Whether [holder] is this session or one it started, without counting as a use of it. */
    fun related(holder: Holder.Session): Boolean
}
