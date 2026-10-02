package net.die.phoneapi.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import io.ktor.events.Events
import io.ktor.http.cio.Request
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.application.PipelineCall
import io.ktor.server.cio.backend.ServerIncomingConnection
import io.ktor.server.cio.backend.ServerRequestScope
import io.ktor.server.cio.backend.startServerConnectionPipeline
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.BaseApplicationEngine
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.streams.asByteWriteChannel
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Ktor engine that accepts HTTP on an abstract-namespace [LocalServerSocket]. CIO's own connector
 * only binds filesystem unix sockets, so each accepted [LocalSocket] is handed to CIO's connection
 * pipeline directly.
 */
@OptIn(InternalAPI::class)
@Suppress("InjectDispatcher") // Accept and CIO run on IO; this engine has no injected dispatcher.
class AbstractHttpEngine(
    environment: ApplicationEnvironment,
    monitor: Events,
    developmentMode: Boolean,
    private val configuration: Configuration,
    private val applicationProvider: () -> Application,
) : BaseApplicationEngine(environment, monitor, developmentMode) {
    class Configuration : BaseApplicationEngine.Configuration() {
        var socketName: String = "phoneapi"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: LocalServerSocket? = null
    private var accept: Thread? = null
    private var stopped = false

    @Suppress("MissingUseCall") // stop() closes the server socket.
    override fun start(wait: Boolean): ApplicationEngine {
        applicationProvider()
        val listening = LocalServerSocket(configuration.socketName)
        server = listening
        stopped = false
        resolvedConnectorsDeferred.complete(emptyList())
        val thread =
            Thread(
                {
                    while (!stopped) {
                        val client =
                            try {
                                listening.accept()
                            } catch (e: IOException) {
                                if (!stopped) environment.log.error("Abstract accept failed", e)
                                break
                            }
                        scope.launch { serve(this, client) }
                    }
                },
                "phoneapi-abstract",
            )
        accept = thread
        thread.start()
        return this
    }

    override fun stop(gracePeriodMillis: Long, timeoutMillis: Long) {
        stopped = true
        runCatching { server?.close() }
        server = null
        accept?.join(gracePeriodMillis)
        accept = null
    }

    private suspend fun serve(caller: CoroutineScope, client: LocalSocket) {
        client.use { socket ->
            val connection =
                ServerIncomingConnection(
                    input = socket.inputStream.toByteReadChannel(),
                    output = socket.outputStream.asByteWriteChannel(),
                    remoteAddress = null,
                    localAddress = null,
                )
            caller
                .startServerConnectionPipeline(connection, IDLE_TIMEOUT) { request ->
                    dispatch(this, request)
                }
                .join()
        }
    }

    private suspend fun dispatch(scope: ServerRequestScope, request: Request) {
        val call =
            CioCalls.open(
                application = applicationProvider(),
                request = request,
                input = scope.input,
                output = scope.output,
                upgraded = scope.upgraded,
                coroutineContext = scope.coroutineContext,
            )
        try {
            pipeline.execute(call, Unit)
        } finally {
            CioCalls.release(call)
        }
    }

    companion object : ApplicationEngineFactory<AbstractHttpEngine, Configuration> {
        private val IDLE_TIMEOUT = 45.seconds

        override fun configuration(configure: Configuration.() -> Unit): Configuration =
            Configuration().apply(configure)

        override fun create(
            environment: ApplicationEnvironment,
            monitor: Events,
            developmentMode: Boolean,
            configuration: Configuration,
            applicationProvider: () -> Application,
        ): AbstractHttpEngine =
            AbstractHttpEngine(
                environment,
                monitor,
                developmentMode,
                configuration,
                applicationProvider,
            )
    }
}

/**
 * [io.ktor.server.cio.CIOApplicationCall] is internal. It lives in this APK, so the constructor is
 * reachable by reflection; R8 is told to keep it.
 */
@Suppress("InjectDispatcher") // CIO runs request work on IO.
private object CioCalls {
    private val ctor =
        Class.forName("io.ktor.server.cio.CIOApplicationCall")
            .declaredConstructors
            .first {
                it.parameterCount == 10
            }
            .apply { isAccessible = true }

    private val release =
        Class.forName("io.ktor.server.cio.CIOApplicationCall")
            .getDeclaredMethod("release\$ktor_server_cio")
            .apply { isAccessible = true }

    fun open(
        application: Application,
        request: Request,
        input: Any?,
        output: Any?,
        upgraded: Any?,
        coroutineContext: Any?,
    ): PipelineCall {
        val io = Dispatchers.IO
        return ctor.newInstance(
            application,
            request,
            input,
            output,
            io,
            io,
            upgraded,
            null,
            null,
            coroutineContext,
        ) as PipelineCall
    }

    fun release(call: PipelineCall) {
        release.invoke(call)
    }
}
