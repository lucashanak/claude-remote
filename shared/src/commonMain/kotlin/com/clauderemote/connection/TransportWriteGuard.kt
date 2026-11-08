package com.clauderemote.connection

import com.clauderemote.util.FileLogger
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * C5 (trace-lan-umrti): the socket of a direct (non-Cloudflare) JSch transport, with a guard on its writes. JSch encrypts
 * and compresses a packet (AES-CTR counter, deflate state) BEFORE it writes it; a write that throws — on Android a write
 * from the main thread throws `NetworkOnMainThreadException` before a byte leaves — leaves the client's cipher one packet
 * ahead of sshd's, and the next packet reads as garbage there (`Corrupted padlen`, `Invalid ssh2 packet type`, or a silent
 * RST/FIN). JSch swallows the exception in places (`ChannelSession.setPtySize`), so it cannot be caught around the call:
 * any failed write here closes the socket at once — the transport is broken, every channel on it ends and reconnects —
 * and says which exception on which thread. Never quietly kept.
 *
 * JSch applies no connect timeout to a socket from a factory: [connectTimeoutMs] does it here (0 = none, like JSch),
 * the name lookup included (kolo 4, adv N6 — JSch's own `Util.createSocket` resolves inside its timed thread too).
 */
internal class TransportWriteGuard(private val connectTimeoutMs: Int) : com.jcraft.jsch.SocketFactory {
    override fun createSocket(host: String, port: Int): Socket {
        val ms = connectTimeoutMs.coerceAtLeast(0)
        if (ms == 0) return Socket().apply { connect(InetSocketAddress(host, port)) }
        val start = System.nanoTime()
        val address = resolve(host, ms.toLong())
        val left = (ms - (System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        return Socket().apply { connect(InetSocketAddress(address, port), left.toInt()) }
    }

    /** The lookup of [host] on a daemon thread of its own, waited for [ms] (a blocking lookup cannot be interrupted). */
    private fun resolve(host: String, ms: Long): java.net.InetAddress {
        val resolver = resolverForTest
        val lookup = java.util.concurrent.FutureTask { resolver?.invoke(host) ?: java.net.InetAddress.getByName(host) }
        Thread(lookup, "dns-lookup").apply { isDaemon = true; start() }
        return try {
            lookup.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw java.net.SocketTimeoutException("lookup of $host took longer than ${ms}ms")
        } catch (e: java.util.concurrent.ExecutionException) {
            throw (e.cause as? java.io.IOException) ?: java.io.IOException(e.cause)
        }
    }

    override fun getInputStream(socket: Socket) = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream {
        val raw = socket.getOutputStream()
        return Guarded(outputForTest?.invoke(raw) ?: raw, socket)
    }

    private class Guarded(private val out: OutputStream, private val socket: Socket) : OutputStream() {
        private val broken = AtomicBoolean(false)

        override fun write(b: Int) = guard { out.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }
        override fun flush() = guard { out.flush() }
        override fun close() = out.close()

        private inline fun guard(op: () -> Unit) {
            val closedBefore = socket.isClosed
            try {
                op()
            } catch (e: Throwable) {
                if (broken.compareAndSet(false, true)) {
                    val where = "Transport write to ${socket.inetAddress?.hostAddress}:${socket.port} failed on thread " +
                        "${Thread.currentThread().name}: ${e.javaClass.name}: ${e.message}"
                    // kolo 4 (adv N7): a socket closed before the write (our own cleanup) is no broken transport
                    if (closedBefore) FileLogger.log(TAG, "$where (the socket was already closed)")
                    else FileLogger.log(TAG, "$where — closing the transport (a packet may have been encrypted but not sent)")
                    try { socket.close() } catch (_: Exception) {}
                }
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "TransportWriteGuard"

        /** The factory of a direct transport; null over Cloudflare (the proxy's WebSocket queues the bytes, see [CloudflareProxy]). */
        fun forServer(useCloudflareProxy: Boolean, connectTimeoutMs: Int): TransportWriteGuard? =
            if (useCloudflareProxy) null else TransportWriteGuard(connectTimeoutMs)

        /** Test seam (kolo 5, V15): stands in for the name lookup (a slow DNS). Null in production. */
        @Volatile internal var resolverForTest: ((String) -> java.net.InetAddress)? = null

        /** Test seam: wraps the socket's raw stream (under the guard). Null in production. */
        @Volatile internal var outputForTest: ((OutputStream) -> OutputStream)? = null
    }
}
