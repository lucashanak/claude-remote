package com.clauderemote.connection

import com.clauderemote.util.FileLogger
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * JSch Proxy that tunnels SSH traffic over a Cloudflare Tunnel WebSocket.
 *
 * Instead of opening a direct TCP connection to the SSH port, this routes
 * the SSH traffic through Cloudflare's edge network:
 *
 *   SSH client -> WebSocket (wss://) -> Cloudflare Edge -> cloudflared daemon -> SSH server
 *
 * Prerequisites on the Cloudflare side:
 * - cloudflared daemon running on the origin server
 * - Tunnel has a public hostname (e.g. ssh.example.com) with service type tcp://IP:22
 *   (NOT ssh:// which is for browser-rendered terminal only)
 *
 * @param hostname The Cloudflare tunnel hostname (e.g. "ssh.example.com")
 * @param cfAccessToken Optional Cloudflare Access JWT for Zero Trust setups
 */
class CloudflareProxy(
    private val hostname: String,
    private val cfAccessToken: String = ""
) : Proxy {

    private var client: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var pipedInput: PipedInputStream? = null
    private var pipedOutput: PipedOutputStream? = null
    private var wsOutputStream: WebSocketOutputStream? = null

    @Volatile
    private var closed = false

    override fun connect(
        socketFactory: SocketFactory?,
        host: String,
        port: Int,
        timeout: Int
    ) {
        FileLogger.log(TAG, "Connecting to Cloudflare tunnel: wss://$hostname/")

        // MUST be HTTP/1.1 — Cloudflare negotiates HTTP/2 via ALPN,
        // but WebSocket upgrade (101 Switching Protocols) only works over HTTP/1.1.
        // HTTP/2 strips Connection: Upgrade hop-by-hop headers -> PROTOCOL_ERROR
        val okClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(timeout.toLong().coerceAtLeast(10000), TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        client = okClient

        // Piped streams: WebSocket onMessage -> PipedOutputStream -> PipedInputStream -> JSch reads
        // Buffer 64KB — SSH traffic can arrive in large chunks
        pipedOutput = PipedOutputStream()
        pipedInput = PipedInputStream(pipedOutput!!, 65536)

        wsOutputStream = WebSocketOutputStream()

        val requestBuilder = Request.Builder()
            .url("wss://$hostname/")
            .header("Connection", "Upgrade")
            .header("Upgrade", "websocket")

        // Optional CF Access token for Zero Trust
        if (cfAccessToken.isNotBlank()) {
            requestBuilder.header("cf-access-token", cfAccessToken)
        }

        val latch = java.util.concurrent.CountDownLatch(1)
        var connectError: String? = null // guarded by latch happens-before

        val ws = okClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                FileLogger.log(TAG, "WebSocket connected to $hostname")
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (closed) return
                try {
                    pipedOutput?.write(bytes.toByteArray())
                    pipedOutput?.flush()
                } catch (e: IOException) {
                    if (!closed) FileLogger.error(TAG, "Pipe write failed", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                FileLogger.error(TAG, "WebSocket failure: ${t.message}", t)
                connectError = t.message ?: "WebSocket connection failed"
                latch.countDown()
                closePipes()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                FileLogger.log(TAG, "WebSocket closing: $code $reason")
                closePipes()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                FileLogger.log(TAG, "WebSocket closed: $code $reason")
                closePipes()
            }
        })

        webSocket = ws
        wsOutputStream?.webSocket = ws

        // Wait for connection
        val connected = latch.await(timeout.toLong().coerceAtLeast(10000), TimeUnit.MILLISECONDS)
        if (!connected || connectError != null) {
            close()
            throw IOException("Cloudflare tunnel connection failed: ${connectError ?: "timeout"}")
        }
    }

    override fun getInputStream(): InputStream = pipedInput
        ?: throw IOException("Not connected")

    override fun getOutputStream(): OutputStream = wsOutputStream
        ?: throw IOException("Not connected")

    override fun getSocket(): Socket? = null // No raw socket — traffic goes over WebSocket

    override fun close() {
        if (closed) return
        closed = true
        FileLogger.log(TAG, "Closing Cloudflare proxy")
        closePipes()
        try { webSocket?.close(1000, "Client closing") } catch (_: Exception) {}
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        webSocket = null
        client = null
    }

    private fun closePipes() {
        try { pipedOutput?.close() } catch (_: Exception) {}
        try { pipedInput?.close() } catch (_: Exception) {}
    }

    /**
     * OutputStream that sends every write() as a binary WebSocket frame.
     */
    private class WebSocketOutputStream : OutputStream() {
        @Volatile
        var webSocket: WebSocket? = null

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val ws = webSocket ?: throw IOException("WebSocket not connected")
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            if (!ws.send(data.toByteString())) {
                throw IOException("WebSocket send failed (queue full or closed)")
            }
        }

        override fun flush() {
            // WebSocket sends are immediate, no buffering needed
        }

        override fun close() {
            webSocket = null
        }
    }

    companion object {
        private const val TAG = "CloudflareProxy"
    }
}
