package com.universalcast.network

import com.universalcast.core.DiagnosticsLog
import com.universalcast.ucp.Ucp
import org.java_websocket.WebSocket
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
        try {
            stop(1000)
        } catch (e: Exception) {
            log.warn("Server stop: ${e.javaClass.simpleName}")
        }
    }

    override fun onStart() {
        started.countDown()
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

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            startError = ex
            started.countDown()
        }
        log.warn("Network error: ${ex.javaClass.simpleName}")
    }
}
