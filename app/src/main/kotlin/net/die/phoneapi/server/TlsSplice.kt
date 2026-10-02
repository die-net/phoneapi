package net.die.phoneapi.server

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/**
 * Terminates TLS and copies bytes to the abstract socket. It does not parse HTTP, so the abstract
 * listener stays the only place routes run.
 */
internal class TlsSplice(
    private val tls: TlsManager,
    private val abstractName: String,
) {
    private var socket: SSLServerSocket? = null
    private var thread: Thread? = null

    fun start(host: String, port: Int) {
        stop()
        val context = SSLContext.getInstance("TLS")
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(tls.keyStore, tls.password)
        context.init(keys.keyManagers, null, null)
        val server =
            context.serverSocketFactory.createServerSocket(
                port,
                BACKLOG,
                InetAddress.getByName(host),
            ) as SSLServerSocket
        socket = server
        thread =
            Thread(
                    {
                        while (!server.isClosed) {
                            val client =
                                try {
                                    server.accept()
                                } catch (e: IOException) {
                                    if (!server.isClosed) Log.w(TAG, "TLS accept failed", e)
                                    break
                                }
                            Thread({ bridge(client) }, "phoneapi-tls-conn").start()
                        }
                    },
                    "phoneapi-tls",
                )
                .also { it.start() }
        Log.i(TAG, "HTTPS splice on $host:$port")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        thread?.join(STOP_WAIT_MS)
        thread = null
    }

    private fun bridge(client: Socket) {
        try {
            LocalSocket().use { local ->
                local.connect(
                    LocalSocketAddress(abstractName, LocalSocketAddress.Namespace.ABSTRACT)
                )
                val outbound =
                    Thread(
                        { copy(client.getInputStream(), local.outputStream) },
                        "phoneapi-tls-out",
                    )
                val inbound =
                    Thread({ copy(local.inputStream, client.getOutputStream()) }, "phoneapi-tls-in")
                outbound.start()
                inbound.start()
                outbound.join()
                inbound.join()
            }
        } catch (e: IOException) {
            Log.w(TAG, "HTTPS splice failed", e)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        try {
            from.copyTo(to)
        } catch (e: IOException) {
            Log.d(TAG, "Splice side closed", e)
        } finally {
            runCatching { to.close() }
        }
    }

    private companion object {
        const val TAG = "PhoneApiServer"
        const val BACKLOG = 50
        const val STOP_WAIT_MS = 1_000L
    }
}
