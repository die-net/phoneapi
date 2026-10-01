package net.die.phoneapi.mcp

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import net.die.phoneapi.server.ServerServices
import net.die.phoneapi.server.token

/** Streamable HTTP MCP at `/mcp`. Tools and resources follow the caller's scopes. */
internal fun Application.installPhoneMcp(services: ServerServices) {
    mcpStatelessStreamableHttp(path = "/mcp") {
        val scopes = call.token.scopes
        val tools = visibleMcpTools(scopes, services.device.capabilities())
        val resources = mcpResources(scopes)
        Server(
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
                ) { request ->
                    tool.call(services, scopes, request)
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
}
