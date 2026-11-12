package com.universalcast.network

import com.universalcast.core.DiagnosticsLog
import com.universalcast.ucp.Ucp
import org.java_websocket.WebSocket
import org.java_websocket.WebSocketImpl
import org.java_websocket.WrappedByteChannel
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.extensions.DefaultExtension
import org.java_websocket.extensions.IExtension
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.IProtocol
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.SSLParametersWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

private class WsConnection(private val ws: WebSocket) : Connection {
    override val id: String = UUID.randomUUID().toString()
    override val remoteAddress: String = ws.remoteSocketAddress?.address?.hostAddress ?: "unknown"

    override fun send(text: String) {
        if (ws.isOpen) ws.send(text)
    }

    override fun close(code: Int, reason: String) {
        ws.close(code, reason)
    }
}

/**
 * The secure endpoint: WSS only (TLS 1.3/1.2) at [Ucp.WS_PATH]. There is no plaintext listener. Frames larger
 * than [Ucp.MAX_MESSAGE_BYTES] are refused by the WebSocket layer itself.
 */
class UcpServer(
    bind: InetSocketAddress,
    sslContext: SSLContext,
    private val handler: UcpProtocolHandler,
    private val log: DiagnosticsLog,
) : WebSocketServer(
    bind,
    listOf<Draft>(
        Draft_6455(listOf<IExtension>(DefaultExtension()), listOf<IProtocol>(Protocol("")), Ucp.MAX_MESSAGE_BYTES)
    ),
) {
    private val connections = ConcurrentHashMap<WebSocket, WsConnection>()
    private val started = CountDownLatch(1)

    @Volatile
    private var startError: Exception? = null

    /**
     * Works around a lost-wakeup race in Java-WebSocket 1.6.0's NIO server: `WebSocketServer.doWrite()` (selector
     * thread) first writes the out-queue empty and only afterwards sets the key's interest back to OP_READ. A message
     * queued by a worker thread in between calls `onWriteDemand()` (OP_READ|OP_WRITE), which that late
     * `interestOps(OP_READ)` then overwrites, so the message stays queued until something else is sent on that
     * connection. Seen as a request whose response never arrives (sender heartbeat/request timeouts under load).
     * This watchdog re-arms write interest for any connection that still has queued or TLS-buffered output.
     */
    private val writeWatchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ucp-write-watchdog").also { it.isDaemon = true } }

    init {
        isReuseAddr = true
        isDaemon = true
        connectionLostTimeout = 15
        val params = sslContext.defaultSSLParameters
        val supported = sslContext.supportedSSLParameters.protocols.toSet()
        val enabled = listOf("TLSv1.3", "TLSv1.2").filter { it in supported }
        check(enabled.isNotEmpty()) { "No secure TLS protocol is available" }
        params.protocols = enabled.toTypedArray()
        params.needClientAuth = false
        setWebSocketFactory(SSLParametersWebSocketServerFactory(sslContext, params))
    }

    /** Starts listening and blocks until the socket is bound (or binding failed). */
    fun startAndWait(timeoutMs: Long) {
        start()
        if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException("Receiver server did not start in time")
        startError?.let { throw IllegalStateException("Receiver server failed to start: ${it.message}", it) }
    }

    fun stopQuietly() {
        writeWatchdog.shutdownNow()
        try {
            stop(1000)
        } catch (e: Exception) {
            log.warn("Server stop: ${e.javaClass.simpleName}")
        }
    }

    override fun onStart() {
        writeWatchdog.scheduleWithFixedDelay({ rearmStalledWrites() }, WATCHDOG_MS, WATCHDOG_MS, TimeUnit.MILLISECONDS)
        started.countDown()
    }

    /** See [writeWatchdog]. Cheap: a handful of connections, two field reads each. */
    internal fun rearmStalledWrites() {
        for (ws in connections.keys) {
            val impl = ws as? WebSocketImpl ?: continue
            try {
                val tlsPending = (impl.channel as? WrappedByteChannel)?.isNeedWrite == true
                if (impl.isOpen && (impl.outQueue.isNotEmpty() || tlsPending)) onWriteDemand(impl)
            } catch (e: Exception) {
                // the connection is closing; the server cleans it up
            }
        }
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if (handshake.resourceDescriptor != Ucp.WS_PATH) {
            conn.close(1008, "unknown path")
            return
        }
        val c = WsConnection(conn)
        connections[conn] = c
        handler.onOpen(c)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        connections[conn]?.let { handler.onText(it, message) }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        connections[conn]?.let { handler.onBinary(it) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        connections.remove(conn)?.let { handler.onClose(it) }
    }

    private companion object {
        const val WATCHDOG_MS = 100L
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            startError = ex
            started.countDown()
        }
        log.warn("Network error: ${ex.javaClass.simpleName}")
    }
}
