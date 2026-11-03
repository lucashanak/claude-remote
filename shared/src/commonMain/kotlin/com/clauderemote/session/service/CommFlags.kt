package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feature flags and kill switch of the communication redesign.
 *
 * Pure resolution ([resolve]) is separated from the process-wide holder
 * ([deviceDefaults], [set], [applyServerOff]) so the implication matrix is
 * testable without any global state.
 *
 * Nothing in this build READS the effective flags except this file, AppSettings
 * and the Settings screen: every stub delegates to today's behaviour. Code that
 * acts on behalf of ONE server must use [effectiveFor] / [effectiveFlow]; only
 * device-wide decisions may look at [deviceDefaults].
 */
object CommFlags {
    // Group names — also the vocabulary of ~/.claude-remote/client-flags.json
    // (`{"off":["lazy","hooks"]}`) and of [applyServerOff].
    const val POOL = "pool"
    const val EXEC = "exec"
    const val HOOKS = "hooks"
    const val LAZY = "lazy"
    const val FG_DEADLINK = "fg_deadlink"
    val ALL_GROUPS: Set<String> = linkedSetOf(POOL, EXEC, HOOKS, LAZY, FG_DEADLINK)

    const val WARM_TABS_MIN = 5
    const val WARM_TABS_MAX = 8
    const val WARM_TABS_DEFAULT = 6
    const val BG_PARK_GRACE_DEFAULT_S = 120
    const val BG_PARK_GRACE_MIN_S = 10
    const val BG_PARK_GRACE_MAX_S = 3600

    /** Shell channels per transport: 8 only with the pool AND the exec transport, else the 3 of the single-connection design. */
    const val SHELL_CAP_LEGACY = 3
    const val SHELL_CAP_V2 = 8

    /** The user's own switches (AppSettings) before implications are applied. */
    data class Raw(
        val pool: Boolean = true,
        val exec: Boolean = true,
        val hooks: Boolean = true,
        val lazy: Boolean = false,
        val fgDeadlink: Boolean = false,
        val warmTabs: Int = WARM_TABS_DEFAULT,
        val bgParkGraceS: Int = BG_PARK_GRACE_DEFAULT_S,
        /** Servers for which every group is OFF. Wins over everything. */
        val legacyServers: Set<String> = emptySet(),
    )

    /** What the code may actually act on. */
    data class Effective(
        val pool: Boolean,
        val exec: Boolean,
        val hooks: Boolean,
        val lazy: Boolean,
        val fgDeadlink: Boolean,
        val warmTabs: Int,
        val bgParkGraceS: Int,
        /** `CommFlags: <x> forced off because <y>` / clamp notes — logged on change. */
        val notes: List<String> = emptyList(),
    ) {
        val shellCap: Int get() = if (pool && exec) SHELL_CAP_V2 else SHELL_CAP_LEGACY
        fun isOn(group: String): Boolean = when (group) {
            POOL -> pool; EXEC -> exec; HOOKS -> hooks; LAZY -> lazy; FG_DEADLINK -> fgDeadlink
            else -> false
        }
    }

    /**
     * Resolve [raw] for one server. Order: legacy set (all off) → server-side
     * force-off ([serverOff]) → implications:
     *  - exec ⇒ pool
     *  - hooks ⇒ pool ∧ exec        (stated explicitly, not only via lazy)
     *  - lazy ⇒ pool ∧ exec ∧ hooks
     *  - fg_deadlink ⇒ pool
     * A group that fails an implication is downgraded and a note is produced.
     */
    fun resolve(raw: Raw, serverId: String? = null, serverOff: Set<String> = emptySet()): Effective {
        val notes = mutableListOf<String>()
        fun off(group: String, because: String) {
            notes += "CommFlags: $group forced off because $because"
        }

        val legacy = serverId != null && serverId in raw.legacyServers
        val srvOff = if (legacy) emptySet() else serverOff

        var pool = raw.pool
        var exec = raw.exec
        var hooks = raw.hooks
        var lazy = raw.lazy
        var fg = raw.fgDeadlink

        if (legacy) {
            pool = false; exec = false; hooks = false; lazy = false; fg = false
            notes += "CommFlags: all groups forced off because server $serverId is in the legacy set"
        } else {
            if (pool && POOL in srvOff) { pool = false; off(POOL, "server-side force-off") }
            if (exec && EXEC in srvOff) { exec = false; off(EXEC, "server-side force-off") }
            if (hooks && HOOKS in srvOff) { hooks = false; off(HOOKS, "server-side force-off") }
            if (lazy && LAZY in srvOff) { lazy = false; off(LAZY, "server-side force-off") }
            if (fg && FG_DEADLINK in srvOff) { fg = false; off(FG_DEADLINK, "server-side force-off") }
        }

        if (exec && !pool) { exec = false; off(EXEC, "$POOL is off") }
        if (hooks && !(pool && exec)) {
            hooks = false
            off(HOOKS, if (!pool) "$POOL is off" else "$EXEC is off")
        }
        if (lazy && !(pool && exec && hooks)) {
            lazy = false
            off(LAZY, if (!pool) "$POOL is off" else if (!exec) "$EXEC is off" else "$HOOKS is off")
        }
        if (fg && !pool) { fg = false; off(FG_DEADLINK, "$POOL is off") }

        val warm = raw.warmTabs.coerceIn(WARM_TABS_MIN, WARM_TABS_MAX)
        if (warm != raw.warmTabs) {
            notes += "CommFlags: comm_warm_tabs ${raw.warmTabs} clamped to $warm (range $WARM_TABS_MIN-$WARM_TABS_MAX)"
        }
        val grace = raw.bgParkGraceS.coerceIn(BG_PARK_GRACE_MIN_S, BG_PARK_GRACE_MAX_S)
        if (grace != raw.bgParkGraceS) {
            notes += "CommFlags: comm_bg_park_grace_s ${raw.bgParkGraceS} clamped to $grace (range $BG_PARK_GRACE_MIN_S-$BG_PARK_GRACE_MAX_S)"
        }
        return Effective(pool, exec, hooks, lazy, fg, warm, grace, notes)
    }

    // ---- process-wide holder (platform entry points set it) ----

    private val lock = Any()
    @Volatile private var raw: Raw = Raw()
    private val serverOff = HashMap<String, Set<String>>()
    private val serverFlows = HashMap<String, MutableStateFlow<Effective>>()
    private val loggedServerNotes = HashMap<String, List<String>>()
    private val _deviceDefaults = MutableStateFlow(resolve(Raw()))

    /**
     * Flags as resolved from the device's own switches ONLY: no legacy set, no
     * server-side force-off. **Never decide anything about a concrete server with
     * this** — it would switch the v2 paths on for a legacy or force-off server.
     * Use [effectiveFor] / [effectiveFlow] for that.
     */
    val deviceDefaults: StateFlow<Effective> = _deviceDefaults.asStateFlow()

    /**
     * Per-server one-shot request "install hooks on this server" (raised by the
     * Settings UI). A pure event: nothing is stored, so a request made while nobody
     * listens is gone and can never turn into a later, unintended install.
     */
    private val _hookInstallRequests = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hookInstallRequests: SharedFlow<String> = _hookInstallRequests.asSharedFlow()

    /**
     * Ask for hooks to be installed on [serverId] now. Returns false, and drops the
     * request, when nobody is collecting [hookInstallRequests] (or the buffer refused
     * it): the caller can tell the user that nothing happened, and the request cannot
     * linger and fire later.
     */
    fun requestHooksInstall(serverId: String): Boolean =
        _hookInstallRequests.subscriptionCount.value > 0 && _hookInstallRequests.tryEmit(serverId)

    /** Called by MainActivity / desktop Main at start and by Settings on every change. */
    fun set(newRaw: Raw): Unit = synchronized(lock) {
        raw = newRaw
        publish()
    }

    /**
     * Server-side force-off (read from `client-flags.json` by the bootstrap).
     * Replaces the set for [serverId]; an empty set clears it. Unknown names are
     * ignored. It can only turn groups OFF; the legacy set still wins.
     */
    fun applyServerOff(serverId: String, off: Set<String>): Unit = synchronized(lock) {
        val known = off.filter { it in ALL_GROUPS }.toSet()
        if (known.isEmpty()) serverOff.remove(serverId) else serverOff[serverId] = known
        publish()
    }

    /** Effective flags for one server (legacy set + server-side force-off applied). */
    fun effectiveFor(serverId: String): Effective = synchronized(lock) {
        resolve(raw, serverId, serverOff[serverId].orEmpty())
    }

    /** Live per-server view; changes with Settings, the legacy set and server-side force-off. */
    fun effectiveFlow(serverId: String): StateFlow<Effective> = synchronized(lock) {
        serverFlows.getOrPut(serverId) { MutableStateFlow(resolve(raw, serverId, serverOff[serverId].orEmpty())) }
            .asStateFlow()
    }

    /** Raw switches as last set (for Settings UI and tests). */
    fun raw(): Raw = raw

    /** Test hook: back to defaults without logging. */
    internal fun resetForTest(): Unit = synchronized(lock) {
        raw = Raw()
        serverOff.clear()
        serverFlows.clear()
        loggedServerNotes.clear()
        _deviceDefaults.value = resolve(Raw())
    }

    /** Callers hold [lock]. */
    private fun publish() {
        val next = resolve(raw)
        val prev = _deviceDefaults.value
        _deviceDefaults.value = next
        for (n in next.notes) if (n !in prev.notes) FileLogger.log(TAG, n)

        // Servers that are visible somewhere (a flow, a force-off, the legacy set):
        // refresh their flow and log what is forced off for them, once per change.
        val ids = serverFlows.keys + serverOff.keys + raw.legacyServers + loggedServerNotes.keys
        for (id in ids) {
            val eff = resolve(raw, id, serverOff[id].orEmpty())
            serverFlows[id]?.value = eff
            val before = loggedServerNotes[id].orEmpty()
            for (n in eff.notes) if (n !in before) FileLogger.log(TAG, "[$id] $n")
            if (eff.notes.isEmpty()) loggedServerNotes.remove(id) else loggedServerNotes[id] = eff.notes
        }
    }

    private const val TAG = "CommFlags"
}
