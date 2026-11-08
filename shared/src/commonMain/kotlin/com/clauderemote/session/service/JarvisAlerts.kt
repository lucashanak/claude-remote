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

/** A lease edge of one server ([JarvisAlertSink.onLeaseChanged]). */
data class JarvisLeaseEdge(
    val serverId: String,
    val alive: Boolean,
    /** Device time of that server's newest heartbeat (null = none seen): completions muted after it went unsaid. */
    val heartbeatAtMs: Long?,
    /**
     * That server's watcher tail is connected right now. A lapse while it is not is the network, not Jarvis: the
     * per-session fallback still happens, but "Jarvis neodpovídá" is not said (rev-f3-app LOW).
     */
    val watcherConnected: Boolean,
)

/** Where the hub hands what happened (the platform posts and cancels its notifications). */
interface JarvisAlertSink {
    /** [alert] is new; [feed] already holds it. */
    fun onAlert(alert: JarvisAlert, feed: JarvisFeed)
    /** The user saw everything ([JarvisAlertHub.acknowledge]) or every pending alert expired. */
    fun onCleared()
    /** One server's lease went live or lapsed (edges only). */
    fun onLeaseChanged(edge: JarvisLeaseEdge)
    /** This device's alert routing changed: surfaces that copy it (the watch) refresh. */
    fun onRoutingChanged() {}
}

/**
 * Jarvis' alerts and heartbeat lease over all servers. Thread-safe: the watcher tails call [onLine] from IO threads.
 *
 * Dedup by id: the tail replays its last 25 lines on every (re)connect, so an id seen once is never raised again.
 * The replay of a server's FIRST connection in this process has no baseline: an alert older than
 * [FIRST_SIGHTING_GRACE_MS] there is history the user may have seen on an earlier run, so it is recorded and not
 * raised. That window is only the replay: it ends with the first fresh heartbeat (lines after it in the file are
 * younger than it) or [COLD_WINDOW_MS] after the connect, whichever comes first (rev-f3-app MED3: a late alert of a
 * long first connection was dropped). Otherwise an alert older than [MAX_AGE_MS] is stale (navrh §3 bod 6).
 *
 * The lease is per server and in time of THIS device: the heartbeat's server ms moved by the clock skew the tail
 * learned. A heartbeat replayed at connect carries its own old time, so a replay never makes a dead jarvis-d look
 * alive. No tail, no heartbeat: the lease lapses by itself after [LEASE_MS] — fail-open needs nothing to happen (R11).
 * Jarvis watches the sessions of his own box only, so a session's alerts are carried by the lease of ITS server
 * (rev-f3-app MED4).
 */
class JarvisAlertHub(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /** navrh §3 bod 11: a heartbeat younger than 3 min. */
        const val LEASE_MS = 3L * 60 * 1000
        const val FIRST_SIGHTING_GRACE_MS = 2L * 60 * 1000
        /** How long after a first connect its replay may still arrive. */
        const val COLD_WINDOW_MS = 5_000L
        /** A heartbeat this young ends the replay window: jarvis-d writes one a minute. */
        const val FRESH_HB_MS = 90_000L
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
    /** Servers whose watcher connected at least once; a server in [coldUntil] is in its first connection's replay. */
    private val connectedOnce = HashSet<String>()
    private val coldUntil = HashMap<String, Long>()
    private val watcherUp = HashSet<String>()
    private var pending = ArrayList<JarvisAlert>()
    private val lastAlive = HashMap<String, Boolean>()

    private val _state = MutableStateFlow(JarvisFeed())
    val state: StateFlow<JarvisFeed> = _state

    @Volatile var sink: JarvisAlertSink? = null

    /**
     * Whether the user is at this device (Android: the app in the foreground; desktop: focused, not minimized, not
     * locked or idle). Showing the Jarvis tab counts as seeing its alerts only then (rev-f3-app LOW).
     */
    @Volatile var presence: () -> Boolean = { true }

    private var ticker: Job? = null

    /** The watcher of [serverId] (re)connected its tail; replayed lines follow. */
    fun connected(serverId: String) {
        synchronized(lock) {
            watcherUp.add(serverId)
            if (connectedOnce.add(serverId)) coldUntil[serverId] = clock() + COLD_WINDOW_MS else coldUntil.remove(serverId)
        }
    }

    /** The watcher of [serverId] lost its tail: a lease lapse from now on is the network's, not Jarvis'. */
    fun disconnected(serverId: String) {
        synchronized(lock) { watcherUp.remove(serverId) }
    }

    private fun isCold(serverId: String, now: Long): Boolean {
        val until = coldUntil[serverId] ?: return false
        if (now < until) return true
        coldUntil.remove(serverId)
        return false
    }

    /**
     * One `claude-remote-jarvis` line of [serverId]'s tail. [skewMs] = device clock minus server clock, as the tail's
     * clock probe measured it. False when the line was not understood.
     */
    fun onLine(serverId: String, line: String, skewMs: Long): Boolean {
        val p = JarvisAlertLine.parse(line) ?: return false
        val now = clock()
        var raised: JarvisAlert? = null
        var edges: List<JarvisLeaseEdge> = emptyList()
        val feed = synchronized(lock) {
            when (p) {
                is JarvisAlertLine.Parsed.Heartbeat -> {
                    val at = p.serverMs + skewMs
                    if (at > (heartbeats[serverId] ?: Long.MIN_VALUE)) heartbeats[serverId] = at
                    if (now - at < FRESH_HB_MS) coldUntil.remove(serverId)
                }
                is JarvisAlertLine.Parsed.Alert -> {
                    val at = p.serverMs + skewMs
                    val age = now - at
                    val fresh = age <= (if (isCold(serverId, now)) FIRST_SIGHTING_GRACE_MS else MAX_AGE_MS)
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
            edges = leaseEdges(now)
            publish(now)
        }
        edges.forEach { e -> sink?.onLeaseChanged(e) }
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

    /** [acknowledge] only while the user is at this device ([presence]); true when it did. */
    fun acknowledgeIfPresent(): Boolean {
        if (!presence()) return false
        acknowledge()
        return true
    }

    /** This device's routing switch moved. */
    fun noteRoutingChanged() {
        sink?.onRoutingChanged()
    }

    /** Re-judges the lease and expires old alerts; the ticker calls it, a test calls it directly. */
    fun tick() {
        val now = clock()
        var cleared = false
        val edges = synchronized(lock) {
            val before = pending.size
            pending.removeAll { now - it.atMs > PENDING_TTL_MS }
            cleared = before > 0 && pending.isEmpty()
            val e = leaseEdges(now)
            publish(now)
            e
        }
        edges.forEach { e -> sink?.onLeaseChanged(e) }
        if (cleared) sink?.onCleared()
    }

    /**
     * The lease of [serverId] now, without waiting for the next tick: the policy asks at the moment an alert is
     * decided. Null = any server (the watch, which has one switch for every session).
     */
    fun alive(serverId: String? = null): Boolean = synchronized(lock) {
        val now = clock()
        if (serverId == null) heartbeats.keys.any { aliveAt(it, now) } else aliveAt(serverId, now)
    }

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

    private fun aliveAt(serverId: String, now: Long): Boolean =
        heartbeats[serverId]?.let { now - it in -LEASE_MS until LEASE_MS } == true

    private fun leaseEdges(now: Long): List<JarvisLeaseEdge> = heartbeats.keys.mapNotNull { id ->
        val alive = aliveAt(id, now)
        if (alive == (lastAlive[id] ?: false)) return@mapNotNull null
        lastAlive[id] = alive
        FileLogger.log(TAG, if (alive) "lease of $id live" else "lease of $id lapsed: no heartbeat for ${LEASE_MS / 1000}s")
        JarvisLeaseEdge(id, alive, heartbeats[id], id in watcherUp)
    }

    private fun publish(now: Long): JarvisFeed {
        val newest = heartbeats.maxByOrNull { it.value }
        val feed = JarvisFeed(heartbeats.keys.any { aliveAt(it, now) }, newest?.value, newest?.key, pending.toList())
        _state.value = feed
        return feed
    }
}

/**
 * Completions this device did not alert because Jarvis carried them (rev-f3-app MED1). When a server's lease lapses,
 * what was muted after its last heartbeat was never said by anyone — jarvis-d was already dead — so those are handed
 * back to be alerted after all. One per session (the newest), at most [MAX], none older than [MAX_AGE_MS].
 * [T] is whatever the platform needs to post it again.
 */
class JarvisMutedCompletions<T>(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val MAX = 50
        const val MAX_AGE_MS = 30L * 60 * 1000
    }

    private data class Entry<T>(val serverId: String, val sessionId: String, val atMs: Long, val payload: T)

    private val entries = ArrayList<Entry<T>>()

    @Synchronized
    fun note(serverId: String, sessionId: String, payload: T) {
        val now = clock()
        entries.removeAll { it.sessionId == sessionId || now - it.atMs > MAX_AGE_MS }
        entries.add(Entry(serverId, sessionId, now, payload))
        while (entries.size > MAX) entries.removeAt(0)
    }

    /** Removes and returns [serverId]'s muted completions newer than [sinceMs] (null = all of them), oldest first. */
    @Synchronized
    fun takeAfter(serverId: String, sinceMs: Long?): List<T> {
        val now = clock()
        val out = entries.filter {
            it.serverId == serverId && (sinceMs == null || it.atMs > sinceMs) && now - it.atMs <= MAX_AGE_MS
        }
        entries.removeAll { it.serverId == serverId }
        return out.map { it.payload }
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
 * The app's own batching on top of jarvis-d's: whether an alert is posted at all and whether it may vibrate.
 * jarvis-d already keeps one interruption per 5 min; repeating that gate here on the phone's clock would silence a
 * block jarvis-d let through a second early (rev-f3-app MED2). The app only spaces vibrations [BUZZ_GAP_MS] apart —
 * against several servers or a replay after a frozen socket delivering two batches together — and that holds for an
 * incident too (one per minute). INFO never vibrates and only updates the merged notification quietly.
 */
class JarvisAlertBatcher(private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val BUZZ_GAP_MS = 60_000L
    }

    private var lastBuzzAt: Long? = null

    /** Null = not posted (routing per session, notifications off, or no live lease: per-session alerts carry it). */
    @Synchronized
    fun decide(alert: JarvisAlert, routing: AlertRouting, alive: Boolean, notificationsEnabled: Boolean): JarvisPost? {
        if (!notificationsEnabled || routing != AlertRouting.VIA_JARVIS || !alive) return null
        if (!alert.kind.urgent) return JarvisPost(buzz = false)
        val now = clock()
        val last = lastBuzzAt
        val buzz = last == null || now - last >= BUZZ_GAP_MS || now < last
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
