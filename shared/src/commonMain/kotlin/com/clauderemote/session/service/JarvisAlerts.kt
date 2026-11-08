package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Jarvis F3: the alerts `jarvis-d` writes for the app, read off the per-server watcher tail that already follows the
 * hook events (no channel of their own). The server decides WHAT is worth saying and batches it (navrh §3: grace
 * periods, one interruption per 5 min, incident at once, dedup and expiry); the app only parses, deduplicates by id,
 * keeps the heartbeat lease and decides how loud a post is.
 */
internal const val JARVIS_ALERTS_PATH = "~/.cache/claude-remote/jarvis-alerts"

/** What an alert is about (the contract's third token). */
enum class JarvisAlertKind(val wire: String) {
    /** Something blocks Lukáš past its grace period: perm, ask, waiting, a Stop ending in a question. */
    BLOCK("block"),
    /** A task given through Jarvis finished (replied, ke kontrole, hotovo, selhalo), a watch fired, a reminder is due. */
    DONE("done"),
    /** Anything that may wait (a briefing, a digest note). */
    INFO("info"),
    /** Many sessions started or ended within a minute: one item for all of them. */
    INCIDENT("incident"),
    ;

    /**
     * "HNED" of navrh §3 bod 10: the kinds that may vibrate a phone or a watch (contract 2026-11-08: block, done,
     * incident). Only INFO joins the merged notification quietly.
     */
    val urgent: Boolean get() = this != INFO

    companion object {
        fun of(wire: String): JarvisAlertKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** One alert as received. [serverMs] is the server's epoch ms; [atMs] the same instant on this device's clock. */
data class JarvisAlert(
    val id: String,
    val kind: JarvisAlertKind,
    val serverMs: Long,
    val atMs: Long,
    /** Czech, one sentence, no paths (the server's promise; the app shows it as plain text). */
    val text: String,
    /** The tmux session it is about, or null ("-"). */
    val tmux: String?,
    val serverId: String,
)

/**
 * The contract's line: `claude-remote-jarvis <id> <block|done|info|incident> <ms> <b64 text> <b64 tmux|->` and the
 * heartbeat `claude-remote-jarvis hb <ms>`. Anything else is not understood and returns null (a torn or future line
 * never breaks the tail).
 */
object JarvisAlertLine {
    const val PREFIX = "claude-remote-jarvis"
    private val ID = Regex("[A-Za-z0-9._:-]{1,64}")
    private const val TEXT_MAX = 600
    private const val TMUX_MAX = 200

    sealed interface Parsed {
        data class Heartbeat(val serverMs: Long) : Parsed
        data class Alert(val id: String, val kind: JarvisAlertKind, val serverMs: Long, val text: String, val tmux: String?) : Parsed
    }

    fun parse(line: String): Parsed? {
        val t = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (t.firstOrNull() != PREFIX) return null
        if (t.size == 3 && t[1] == "hb") return t[2].toLongOrNull()?.takeIf { it > 0 }?.let { Parsed.Heartbeat(it) }
        if (t.size != 6) return null
        val id = t[1].takeIf { ID.matches(it) && it != "hb" } ?: return null
        val kind = JarvisAlertKind.of(t[2]) ?: return null
        val ms = t[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val text = b64(t[4])?.let(::clean)?.takeIf { it.isNotBlank() } ?: return null
        val tmux = if (t[5] == "-") null else (b64(t[5])?.let(::clean)?.takeIf { it.isNotBlank() && it.length <= TMUX_MAX } ?: return null)
        return Parsed.Alert(id, kind, ms, text.take(TEXT_MAX), tmux)
    }

    private fun b64(s: String): String? = try {
        String(java.util.Base64.getDecoder().decode(s), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** One line of plain text: control characters (a newline in a notification, an escape) become spaces. */
    private fun clean(s: String): String = s.map { if (it < ' ' || it == '\u007f') ' ' else it }.joinToString("").trim()
}

/** What the app knows of Jarvis right now ([JarvisAlertHub.state]). */
data class JarvisFeed(
    /** The heartbeat lease is live: some server's `hb` is younger than [JarvisAlertHub.LEASE_MS]. */
    val alive: Boolean = false,
    /** Device time of the newest heartbeat, null = none seen in this process. */
    val heartbeatAtMs: Long? = null,
    /** The server whose heartbeat is newest: where Jarvis' CLI is run. */
    val serverId: String? = null,
    /** Alerts not yet seen by the user, newest first. */
    val pending: List<JarvisAlert> = emptyList(),
)

/** Where the hub hands what happened (the platform posts and cancels its notifications). */
interface JarvisAlertSink {
    /** [alert] is new; [feed] already holds it. */
    fun onAlert(alert: JarvisAlert, feed: JarvisFeed)
    /** The user saw everything ([JarvisAlertHub.acknowledge]) or every pending alert expired. */
    fun onCleared()
    /** The lease went live or lapsed (edges only). */
    fun onLeaseChanged(alive: Boolean)
}

/**
 * Jarvis' alerts and heartbeat over all servers. Thread-safe: the watcher tails call [onLine] from IO threads.
 *
 * Dedup by id: the tail replays its last 25 lines on every (re)connect, so an id seen once is never raised again.
 * The first connection of a server in this process has no baseline: an alert older than [FIRST_SIGHTING_GRACE_MS]
 * there is history the user may have seen on an earlier run, so it is recorded and not raised. Later, an alert older
 * than [MAX_AGE_MS] is stale (navrh §3 bod 6: a stale item is not said) — a phone whose socket froze still gets what
 * jarvis-d said a few minutes ago.
 *
 * The lease is time on THIS device: the heartbeat's server ms moved by the clock skew the tail learned. A heartbeat
 * replayed at connect carries its own old time, so a replay never makes a dead jarvis-d look alive. No tail, no
 * heartbeat: the lease lapses by itself after [LEASE_MS] — fail-open needs nothing to happen (R11).
 */
class JarvisAlertHub(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /** navrh §3 bod 11: a heartbeat younger than 3 min. */
        const val LEASE_MS = 3L * 60 * 1000
        const val FIRST_SIGHTING_GRACE_MS = 2L * 60 * 1000
        const val MAX_AGE_MS = 30L * 60 * 1000
        /** A pending alert nobody looked at for this long is dropped from the count (it is stale by then). */
        const val PENDING_TTL_MS = 2L * 60 * 60 * 1000
        const val PENDING_MAX = 50
        private const val SEEN_MAX = 1000
        private const val TICK_MS = 20_000L
        private const val TAG = "JarvisAlerts"
    }

    private val lock = Any()
    private val seen = LinkedHashSet<String>()
    private val heartbeats = HashMap<String, Long>()
    /** Servers whose watcher connected at least once; a server in [cold] is on its first connection. */
    private val connectedOnce = HashSet<String>()
    private val cold = HashSet<String>()
    private var pending = ArrayList<JarvisAlert>()
    private var lastAlive = false

    private val _state = MutableStateFlow(JarvisFeed())
    val state: StateFlow<JarvisFeed> = _state

    @Volatile var sink: JarvisAlertSink? = null

    private var ticker: Job? = null

    /** The watcher of [serverId] (re)connected its tail; replayed lines follow. */
    fun connected(serverId: String) {
        synchronized(lock) {
            if (connectedOnce.add(serverId)) cold.add(serverId) else cold.remove(serverId)
        }
    }

    /**
     * One `claude-remote-jarvis` line of [serverId]'s tail. [skewMs] = device clock minus server clock, as the tail's
     * clock probe measured it. False when the line was not understood.
     */
    fun onLine(serverId: String, line: String, skewMs: Long): Boolean {
        val p = JarvisAlertLine.parse(line) ?: return false
        val now = clock()
        var raised: JarvisAlert? = null
        var lease: Boolean? = null
        val feed = synchronized(lock) {
            when (p) {
                is JarvisAlertLine.Parsed.Heartbeat -> {
                    val at = p.serverMs + skewMs
                    if (at > (heartbeats[serverId] ?: Long.MIN_VALUE)) heartbeats[serverId] = at
                }
                is JarvisAlertLine.Parsed.Alert -> {
                    val at = p.serverMs + skewMs
                    val age = now - at
                    val fresh = age <= (if (serverId in cold) FIRST_SIGHTING_GRACE_MS else MAX_AGE_MS)
                    if (seen.add(p.id)) {
                        while (seen.size > SEEN_MAX) seen.remove(seen.first())
                        if (fresh) {
                            val a = JarvisAlert(p.id, p.kind, p.serverMs, at, p.text, p.tmux, serverId)
                            pending.add(0, a)
                            while (pending.size > PENDING_MAX) pending.removeAt(pending.size - 1)
                            raised = a
                        } else {
                            FileLogger.log(TAG, "alert ${p.id} (${p.kind.wire}) from $serverId is ${age}ms old: recorded, not raised")
                        }
                    }
                }
            }
            lease = leaseEdge(now)
            publish(now)
        }
        lease?.let { sink?.onLeaseChanged(it) }
        raised?.let { a ->
            FileLogger.log(TAG, "alert ${a.id} (${a.kind.wire}) from $serverId, ${feed.pending.size} pending")
            sink?.onAlert(a, feed)
        }
        return true
    }

    /** The user looked at Jarvis' alerts (the Jarvis tab of the Velín, the merged notification). */
    fun acknowledge() {
        val had = synchronized(lock) {
            val had = pending.isNotEmpty()
            pending = ArrayList()
            publish(clock())
            had
        }
        if (had) sink?.onCleared()
    }

    /** Re-judges the lease and expires old alerts; the ticker calls it, a test calls it directly. */
    fun tick() {
        val now = clock()
        var cleared = false
        val lease = synchronized(lock) {
            val before = pending.size
            pending.removeAll { now - it.atMs > PENDING_TTL_MS }
            cleared = before > 0 && pending.isEmpty()
            val edge = leaseEdge(now)
            publish(now)
            edge
        }
        lease?.let { sink?.onLeaseChanged(it) }
        if (cleared) sink?.onCleared()
    }

    /** The lease now, without waiting for the next tick: the policy asks at the moment an alert is decided. */
    fun alive(): Boolean = synchronized(lock) { aliveAt(clock()) }

    /** Keeps the lease honest while no line arrives (a dead jarvis-d writes nothing). Started on the first line. */
    fun ensureTicker(scope: CoroutineScope) {
        synchronized(lock) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    tick()
                }
            }
        }
    }

    private fun aliveAt(now: Long): Boolean = heartbeats.values.any { now - it in -LEASE_MS until LEASE_MS }

    private fun leaseEdge(now: Long): Boolean? {
        val alive = aliveAt(now)
        if (alive == lastAlive) return null
        lastAlive = alive
        FileLogger.log(TAG, if (alive) "lease live" else "lease lapsed: no heartbeat for ${LEASE_MS / 1000}s")
        return alive
    }

    private fun publish(now: Long): JarvisFeed {
        val newest = heartbeats.maxByOrNull { it.value }
        val feed = JarvisFeed(aliveAt(now), newest?.value, newest?.key, pending.toList())
        _state.value = feed
        return feed
    }
}

/** How "Claude needs input" reaches this device (per device, navrh §4 Upozornění v aplikaci). */
enum class AlertRouting {
    /** Today's behaviour: every session alerts by itself. */
    PER_SESSION,
    /** Completions come batched from Jarvis while his lease is live; perm/ask still alert by themselves. */
    VIA_JARVIS,
    ;

    companion object {
        fun of(s: String?): AlertRouting = if (s == "jarvis") VIA_JARVIS else PER_SESSION
    }

    val wire: String get() = if (this == VIA_JARVIS) "jarvis" else "session"
}

/** How one Jarvis alert is posted. */
data class JarvisPost(val buzz: Boolean)

/**
 * The app's own batching on top of jarvis-d's: whether an alert is posted at all and whether it may buzz. jarvis-d
 * already merges items into one line per batch and keeps one interruption per 5 min; the app guards the same rule
 * once more because several servers (or a replay after a frozen socket) can deliver two batches close together.
 * An incident always buzzes (navrh §3 bod 4); INFO never does and only updates the merged notification quietly.
 */
class JarvisAlertBatcher(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val BUZZ_GAP_MS = 5L * 60 * 1000
    }

    private var lastBuzzAt: Long? = null

    /** Null = not posted (routing per session, notifications off, or no live lease: per-session alerts carry it). */
    @Synchronized
    fun decide(alert: JarvisAlert, routing: AlertRouting, alive: Boolean, notificationsEnabled: Boolean): JarvisPost? {
        if (!notificationsEnabled || routing != AlertRouting.VIA_JARVIS || !alive) return null
        if (!alert.kind.urgent) return JarvisPost(buzz = false)
        val now = clock()
        val last = lastBuzzAt
        val buzz = alert.kind == JarvisAlertKind.INCIDENT || last == null || now - last >= BUZZ_GAP_MS || now < last
        if (buzz) lastBuzzAt = now
        return JarvisPost(buzz)
    }
}

/** The one merged notification ("Jarvis · N"), the same text on the phone, the desktop and the watch. */
object JarvisMerged {
    const val MAX_LINES = 5

    fun title(feed: JarvisFeed): String = "Jarvis · ${feed.pending.size}"

    /** Newest first, one line per alert, the urgent glyph in front of what blocks. */
    fun lines(feed: JarvisFeed): List<String> = feed.pending.take(MAX_LINES).map { a ->
        when (a.kind) {
            JarvisAlertKind.BLOCK -> "▲ "
            JarvisAlertKind.INCIDENT -> "⚠ "
            JarvisAlertKind.DONE -> "✓ "
            JarvisAlertKind.INFO -> "• "
        } + a.text
    } + if (feed.pending.size > MAX_LINES) listOf("… a ${feed.pending.size - MAX_LINES} dalších") else emptyList()

    fun text(feed: JarvisFeed): String = lines(feed).joinToString("\n")

    const val FAIL_OPEN_TEXT = "Jarvis neodpovídá – upozornění jdou zase přímo ze sessions"
}
