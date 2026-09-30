package net.die.phoneapi.mcp

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import net.die.phoneapi.AppGraph
import net.die.phoneapi.server.token

/** Streamable HTTP MCP at `/mcp`. Tools and resources follow the caller's scopes. */
internal fun Application.installPhoneMcp(graph: AppGraph) {
    mcpStatelessStreamableHttp(path = "/mcp") {
        val scopes = call.token.scopes
        val tools = visibleMcpTools(scopes, graph.deviceInfo.capabilities())
        val resources = mcpResources(scopes)
        Server(
            Implementation(name = "phoneapi", version = graph.deviceInfo.info().appVersion),
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
                    tool.call(graph, scopes, request)
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
                                    text = resource.read(graph),
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
