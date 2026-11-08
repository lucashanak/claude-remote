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
    /** What JSch reads: the pipe, noting when its last byte arrived (C1, K-2: the keepalive's clock). */
    private var keepAliveInput: KeepAliveInput? = null
    /** C1 round 2: the keepalive over this tunnel (and the handshake's limit before it), see [TunnelKeepAlive]. */
    @Volatile private var keepAlive: TunnelKeepAlive? = null
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
        // DIAGNOSTIC (data-usage hunt): who opens this connection? Log the
        // app-frame call chain so we can pin the periodic fresh-connection churn.
        run {
            val frames = Throwable().stackTrace
                .filter { it.className.startsWith("com.clauderemote") }
                .take(8)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
            FileLogger.log(TAG, "cf-open-by: $frames")
        }

        // MUST be HTTP/1.1 — Cloudflare negotiates HTTP/2 via ALPN,
        // but WebSocket upgrade (101 Switching Protocols) only works over HTTP/1.1.
        // HTTP/2 strips Connection: Upgrade hop-by-hop headers -> PROTOCOL_ERROR
        val okClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            // Finite read timeout as a BACKSTOP of the WebSocket itself. The liveness
            // signals are the SSH keepalive (see [KeepAliveInput], armed after the
            // handshake) end to end, and pingInterval below for the WebSocket leg only
            // (OkHttp fails the WS if a pong is missed). On a silently half-open
            // socket — especially when Android Doze / aggressive OEM battery
            // management pauses OkHttp's ping scheduler — the ping may never fire and
            // a 0 (infinite) readTimeout left the JSch read blocked FOREVER. 60s sits
            // well above the 15s ping (a healthy-but-quiet tunnel still gets a pong
            // every 15s, which counts as a read and resets the deadline), so it never
            // kills a live link — it only releases a truly dead one.
            .readTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(timeout.toLong().coerceAtLeast(10000), TimeUnit.MILLISECONDS)
            // 15s ping: on Starlink the public IP changes on satellite handovers
            // and silently kills the underlying TCP (no RST reaches us), so the
            // pong-timeout is our fastest dead-link signal — a tighter interval
            // means the auto-reconnect fires sooner after a handover drop.
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        client = okClient

        // Piped streams: WebSocket onMessage -> PipedOutputStream -> PipedInputStream -> JSch reads
        // Buffer 64KB — SSH traffic can arrive in large chunks
        pipedOutput = PipedOutputStream()
        pipedInput = PipedInputStream(pipedOutput!!, 65536)
        keepAliveInput = KeepAliveInput(pipedInput!!)

        wsOutputStream = WebSocketOutputStream()

        tunnelForTest?.let { open ->
            // the seam (tests): the WebSocket leg replaced by [open]; everything else is this proxy's own code
            val tunnel = open({ bytes -> deliver(bytes) }, { closePipes() })
            wsOutputStream?.send = { tunnel.send(it) }
            testTunnel = tunnel
            startKeepAlive(timeout)
            return
        }

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

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = deliver(bytes.toByteArray())

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
        wsOutputStream?.send = { ws.send(it.toByteString()) }

        // Wait for connection
        val connected = latch.await(timeout.toLong().coerceAtLeast(10000), TimeUnit.MILLISECONDS)
        if (!connected || connectError != null) {
            close()
            throw IOException("Cloudflare tunnel connection failed: ${connectError ?: "timeout"}")
        }
        startKeepAlive(timeout)
    }

    /** Bytes from the tunnel into the pipe JSch reads. */
    private fun deliver(bytes: ByteArray) {
        if (closed) return
        try {
            pipedOutput?.write(bytes)
            pipedOutput?.flush()
            keepAliveInput?.dataArrived()
        } catch (e: IOException) {
            if (!closed) FileLogger.error(TAG, "Pipe write failed", e)
        }
    }

    /**
     * The SSH handshake that JSch now runs over the tunnel has no read timeout of its own (no socket), so it gets
     * [connectTimeout] (at least 10 s, like the WebSocket's own connect); [armKeepAlive] then switches to the keepalive.
     */
    private fun startKeepAlive(connectTimeout: Int) {
        val input = keepAliveInput ?: return
        keepAlive = TunnelKeepAlive(input, connectTimeout.toLong().coerceAtLeast(10_000)) { why ->
            FileLogger.log(TAG, "Cloudflare tunnel to $hostname: $why — closing it")
            close()
        }.also { it.start() }
    }

    override fun getInputStream(): InputStream = keepAliveInput
        ?: throw IOException("Not connected")

    override fun getOutputStream(): OutputStream = wsOutputStream
        ?: throw IOException("Not connected")

    override fun getSocket(): Socket? = null // No raw socket — traffic goes over WebSocket

    /**
     * Call right after `session.connect` (C1, K-2): from now on [TunnelKeepAlive] keeps the session alive the way JSch
     * does on a socket — a keepalive after each interval without incoming data, the transport closed after
     * `interval x (serverAliveCountMax + 1)` without any. JSch itself never sends one over this proxy (it does so only when a
     * socket read times out, and there is no socket). Before this call the handshake's limit applies. The session is also
     * marked as one that rides Cloudflare ([ChannelOpen.markCloudflare]).
     */
    fun armKeepAlive(session: com.jcraft.jsch.Session) {
        keepAlive?.arm(session)
        ChannelOpen.markCloudflare(session)
    }

    override fun close() {
        if (closed) return
        closed = true
        FileLogger.log(TAG, "Closing Cloudflare proxy")
        keepAlive?.stop()
        try { testTunnel?.close() } catch (_: Exception) {}
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
        /** Sends one binary frame; false = queue full or closed. */
        @Volatile
        var send: ((ByteArray) -> Boolean)? = null

        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()), 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val ws = send ?: throw IOException("WebSocket not connected")
            val data = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            if (!ws(data)) {
                throw IOException("WebSocket send failed (queue full or closed)")
            }
        }

        override fun flush() {
            // WebSocket sends are immediate, no buffering needed
        }

        override fun close() {
            send = null
        }
    }

    /** The seam's tunnel of this proxy (tests only). */
    @Volatile private var testTunnel: TestTunnel? = null

    /** The WebSocket leg as the seam sees it (tests). */
    internal interface TestTunnel {
        fun send(bytes: ByteArray): Boolean
        fun close()
    }

    companion object {
        private const val TAG = "CloudflareProxy"

        /**
         * A seam for tests (C1 round 2, R1/R2/R4): replaces the OkHttp WebSocket with a tunnel the test opens — it gets
         * the bytes for JSch and a callback for its end, returns how to send and close. Null in production.
         */
        @Volatile internal var tunnelForTest: ((onBytes: (ByteArray) -> Unit, onEnd: () -> Unit) -> TestTunnel)? = null
    }
}

/**
 * The stream JSch reads a tunnel from (C1): [pipe] as it is, plus the time its last byte arrived ([lastByteNanos], set by
 * the feed through [dataArrived]) — the clock of [TunnelKeepAlive]. It never throws into JSch's read: a timeout thrown in
 * the middle of an SSH packet made JSch drop the bytes it had read and lose the stream (review C1-1, adv 5).
 */
internal class KeepAliveInput(private val pipe: PipedInputStream) : InputStream() {
    @Volatile var lastByteNanos: Long = System.nanoTime()
        private set

    /** Bytes were written into [pipe]. */
    fun dataArrived() { lastByteNanos = System.nanoTime() }

    /** The keepalive starts counting now (it was armed). */
    fun touch() { lastByteNanos = System.nanoTime() }

    override fun read(): Int = pipe.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int = pipe.read(b, off, len)

    override fun available(): Int = pipe.available()

    override fun close() = pipe.close()
}

/**
 * The keepalive of a Cloudflare tunnel (C1 round 2). JSch sends its keepalive only when a socket read times out, and a
 * proxy has no socket; so a timer does it with JSch's public `sendKeepAliveMsg`, on JSch's own rules:
 *  - armed ([arm], after the handshake): every interval (`serverAliveInterval`, read on every tick, so the background
 *    interval applies at once) without an incoming byte one keepalive; after `interval x (serverAliveCountMax + 1)`
 *    without any byte the tunnel is dead ([onDead]) — the same ~30 s (10 s x 3) as a direct socket;
 *  - before that (the handshake): no byte for [handshakeMs] = dead.
 * Bytes are bytes: a reply to the keepalive, a WebSocket frame of any channel. The clock is monotonic, so a process
 * frozen by Doze wakes with the time it ran, not the time it slept (no false death after a wake).
 */
internal class TunnelKeepAlive(
    private val input: KeepAliveInput,
    private val handshakeMs: Long,
    private val onDead: (String) -> Unit,
) {
    @Volatile private var session: com.jcraft.jsch.Session? = null
    @Volatile private var lastSentNanos = 0L
    @Volatile private var task: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var over = false

    fun start() {
        input.touch()
        task = TICKER.scheduleWithFixedDelay({ tick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
    }

    fun arm(s: com.jcraft.jsch.Session) {
        input.touch()
        session = s
    }

    fun stop() {
        over = true
        task?.cancel(false)
    }

    internal fun tick(now: Long = System.nanoTime()) {
        if (over) return
        val idleMs = (now - input.lastByteNanos) / 1_000_000
        val s = session
        if (s == null) {
            if (idleMs >= handshakeMs) dead("no data within the handshake's ${handshakeMs}ms")
            return
        }
        val interval = try { s.serverAliveInterval } catch (_: Exception) { 0 }
        if (interval <= 0) return
        val count = try { s.serverAliveCountMax } catch (_: Exception) { 1 }.coerceAtLeast(0)
        if (idleMs >= interval.toLong() * (count + 1)) {
            dead("no data for ${idleMs}ms (keepalive ${interval}ms x ${count + 1})")
            return
        }
        if (idleMs >= interval && (now - lastSentNanos) / 1_000_000 >= interval) {
            lastSentNanos = now
            try { s.sendKeepAliveMsg() } catch (_: Exception) { /* the transport is going down: its read loop says why */ }
        }
    }

    private fun dead(why: String) {
        stop()
        onDead(why)
    }

    companion object {
        private const val TICK_MS = 250L
        private val TICKER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "cf-keepalive").apply { isDaemon = true }
        }
    }
}
