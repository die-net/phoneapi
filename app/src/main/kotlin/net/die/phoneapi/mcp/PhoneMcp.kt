package net.die.phoneapi.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import net.die.phoneapi.lease.Holder
import net.die.phoneapi.model.ApiError
import net.die.phoneapi.model.Scope
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.token

/**
 * Stateful Streamable HTTP MCP at `/mcp`. `initialize` starts a session with an id PhoneAPI picks;
 * later requests carry it in `Mcp-Session-Id` along with the token that started it. POSTs are
 * answered with JSON, GET is refused since PhoneAPI sends nothing unprompted, and `DELETE` ends the
 * session. Tools and resources follow the caller's scopes at `initialize`. DNS rebinding protection
 * stays off: the rest of the API is a superset of these tools and does not check Host.
 */
internal fun Application.installPhoneMcp(services: ServerServices, sessions: McpSessions) {
    routing {
        route("/mcp") {
            post {
                val id = call.request.header(SESSION_HEADER)
                if (id == null) {
                    startSession(services, sessions, call)
                    return@post
                }
                val session = sessions.get(id)
                when {
                    session == null -> call.respond(HttpStatusCode.NotFound, noSession())
                    session.tokenId != call.token.id -> call.respond(HttpStatusCode.NotFound)
                    else -> {
                        session.touch(sessions.clock())
                        serve(session, call)
                    }
                }
            }
            get {
                call.response.header(HttpHeaders.Allow, "POST, DELETE")
                call.respond(HttpStatusCode.MethodNotAllowed)
            }
            delete {
                val session = call.request.header(SESSION_HEADER)?.let(sessions::get)
                when {
                    session == null -> call.respond(HttpStatusCode.NotFound, noSession())
                    session.tokenId != call.token.id -> call.respond(HttpStatusCode.NotFound)
                    else -> session.transport.handleRequest(null, call)
                }
            }
        }
    }
}

private suspend fun startSession(
    services: ServerServices,
    sessions: McpSessions,
    call: ApplicationCall,
) {
    val token = call.token
    sessions.admit(token.id)
    val id = sessions.newId()
    val transport =
        StreamableHttpServerTransport(
            StreamableHttpServerTransport.Configuration(
                enableJsonResponse = true,
                enableDnsRebindingProtection = false,
            )
        )
    transport.setSessionIdGenerator { id }
    val server = phoneServer(services, token.scopes, Holder.Session(id))
    val session = McpSession(id, token.id, token.name, transport, server, sessions.clock())
    transport.setOnSessionInitialized { sessions.add(session) }
    transport.setOnSessionClosed { sessions.closed(it) }
    server.createSession(transport)
    transport.handleRequest(null, call)
    // A POST without a session id that was not an initialize is refused by the transport, and
    // leaves nothing behind.
    if (transport.sessionId == null) server.close()
}

/**
 * Hands [call] to the session's transport. A session that ends while the call waits, as on `DELETE`
 * or expiry, cancels it; the client then gets a 404 rather than an internal error.
 */
private suspend fun serve(session: McpSession, call: ApplicationCall) = coroutineScope {
    val handled = async { session.transport.handleRequest(null, call) }
    handled.join()
    // join throws when this call is canceled, as when its client goes away. Otherwise the
    // transport only cancels a call when the session closes.
    if (handled.isCancelled) {
        if (!call.response.isCommitted) {
            call.respond(
                HttpStatusCode.NotFound,
                ApiError("not_found", "The MCP session ended"),
            )
        }
    } else {
        handled.await()
    }
}

private fun noSession() =
    ApiError("not_found", "No such MCP session. Start a new one with initialize.")

/** The MCP server for one session, with the tools and resources [scopes] allow. */
private fun phoneServer(shared: ServerServices, scopes: Set<Scope>, holder: Holder): Server {
    // The session's tools see only its own tabs and unowned ones.
    val services =
        shared.copy(
            browser = shared.tabs.browserFor(holder),
            waits = shared.tabs.waitsFor(holder, shared.waits),
        )
    val tools = visibleMcpTools(scopes, services.device.capabilities())
    val resources = mcpResources(scopes)
    return Server(
        Implementation(name = "phoneapi", version = services.device.versionName()),
        ServerOptions(
            capabilities =
                ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                    resources =
                        if (resources.isEmpty()) {
                            null
                        } else {
                            ServerCapabilities.Resources(listChanged = false, subscribe = false)
                        },
                )
        ),
    ) {
        tools.forEach { tool ->
            addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.schema,
                toolAnnotations = tool.annotations,
            ) { asked ->
                val request = resolveInput(tool.name, asked, services.lease.holds(holder))
                val screen = takesScreen(tool.name, request.params.arguments)
                leased(services.lease, holder, screen) { tool.call(services, scopes, request) }
            }
        }
        resources.forEach { resource ->
            addResource(
                uri = resource.uri,
                name = resource.name,
                description = resource.description,
                mimeType = resource.mimeType,
            ) { _ ->
                ReadResourceResult(
                    contents =
                        listOf(
                            TextResourceContents(
                                text = resource.read(services),
                                uri = resource.uri,
                                mimeType = resource.mimeType,
                            )
                        )
                )
            }
        }
    }
}

private const val SESSION_HEADER = "Mcp-Session-Id"
