package com.clauderemote.session.service

import com.clauderemote.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-server bootstrap: single-flight `ensure`, memoization, `client-flags.json`
 * force-off decided on the server, 6 h re-ensure. Implementation:
 * [DefaultServerBootstrap]; [NoopServerBootstrap] is today's behaviour (the per-tab
 * ensure in the orchestrator).
 */
internal interface ServerBootstrap {
    /** Idempotent, memoized on success. */
    suspend fun ensure(serverId: String)
    /** Forget the memo (e.g. streamd EOF without `hello`). */
    fun invalidate(serverId: String)
    /** Re-read `client-flags.json` (foreground, every 10 min). */
    suspend fun refreshFlags(serverId: String)
}

/** Stub: today's behaviour is the per-tab ensure in NotificationService/TranscriptService, so nothing to do here. */
internal object NoopServerBootstrap : ServerBootstrap {
    override suspend fun ensure(serverId: String) {}
    override fun invalidate(serverId: String) {}
    override suspend fun refreshFlags(serverId: String) {}
}

// ---------------------------------------------------------------------------
// Implementation (A3)
// ---------------------------------------------------------------------------

/**
 * What the user did in Settings for hooks v2, per server, and what the server said
 * back. Global like [CommFlags] (Settings is a plain composable; the bootstrap is
 * wired elsewhere); [shared] is the instance the app uses, tests create their own.
 *
 * Nothing here is persisted: a request made while no bootstrap listens is gone and
 * can never become a later, unintended install or removal.
 */
internal class HookInstallControl {
    data class Status(
        val state: HookCommands.V2State = HookCommands.V2State.UNKNOWN,
        /** `~/.claude-remote/hookev.off` exists: hooks installed or not, they emit nothing. */
        val offSwitch: Boolean = false,
        val lastResult: HookCommands.V2Result = HookCommands.V2Result.NOT_RUN,
        val atMs: Long = 0,
        val busy: Boolean = false,
    )

    private val _removeRequests = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val removeRequests: SharedFlow<String> = _removeRequests.asSharedFlow()

    private val _status = MutableStateFlow<Map<String, Status>>(emptyMap())
    val status: StateFlow<Map<String, Status>> = _status.asStateFlow()

    /** "Remove hooks v2 from [serverId] now". False when nothing listens (the request is dropped). */
    fun requestRemove(serverId: String): Boolean =
        _removeRequests.subscriptionCount.value > 0 && _removeRequests.tryEmit(serverId)

    internal fun update(serverId: String, f: (Status) -> Status) {
        _status.update { it + (serverId to f(it[serverId] ?: Status())) }
    }

    companion object {
        /** The instance Settings and the bootstrap share. */
        val shared = HookInstallControl()

        /**
         * Why hooks must not be installed for a server with these effective flags, or null
         * when they may be: legacy set, a server-side force-off, a dependency (`pool`,
         * `exec`) that is off, or the device's own `comm_hooks` switch.
         */
        fun installBlockedReason(eff: CommFlags.Effective): String? {
            if (eff.hooks) return null
            return eff.notes.firstOrNull { it.contains("legacy set") }?.substringAfter("because ")
                ?: eff.notes.firstOrNull { it.startsWith("CommFlags: ${CommFlags.HOOKS} forced off because ") }?.substringAfter("because ")
                ?: "comm_hooks is switched off on this device"
        }
    }
}

/** `~/.claude-remote/client-flags.json` as the client reads it. */
internal sealed interface ClientFlags {
    /** No file: nothing is forced off (and an earlier force-off is lifted). */
    data object Absent : ClientFlags
    /** Not JSON, or not the expected shape: NOTHING is switched off. */
    data object Unparseable : ClientFlags
    data class Off(val groups: Set<String>) : ClientFlags

    companion object {
        /** `{"off":["lazy","hooks"]}`; unknown names are kept here and ignored by [CommFlags.applyServerOff]. */
        fun parse(text: String?): ClientFlags {
            if (text == null || text.isBlank()) return Absent
            return try {
                val el = Json.parseToJsonElement(text)
                val obj = el as? JsonObject ?: return Unparseable
                val off = obj["off"] ?: return Off(emptySet())
                val arr = off as? JsonArray ?: return Unparseable
                Off(arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet())
            } catch (_: Exception) {
                Unparseable
            }
        }
    }
}

/** The single bootstrap exec: what it runs and how its output is read. */
internal object BootstrapScript {
    private const val FLAGS = "===CRB flags==="
    private const val HOOKS = "===CRB hooks==="
    private const val HOOKS_V2 = "===CRB hooksv2==="
    private const val STREAMD = "===CRB streamd==="
    private const val SESSIONOPS = "===CRB sessionops==="
    private const val END = "===CRB end==="

    /** `cat client-flags.json` (bounded), delimited, then a newline. */
    private val FLAGS_STEP =
        "echo '$FLAGS'; CF=\"\$HOME/.claude-remote/client-flags.json\"; [ -f \"\$CF\" ] && head -c 4096 \"\$CF\"; echo"

    /**
     * Server-side decision whether hooks are forced off, taken in the shell so the
     * (riskiest) install step cannot run even once for a server that says no. `hooks`
     * is off when listed itself and also when `pool` or `exec` is (hooks need both, see
     * [CommFlags.resolve]). A corrupt file means "nothing is off". Only the first 4096
     * bytes are read, exactly as the client does, so both sides judge a longer file by the same prefix.
     */
    private val OFF_DECISION = """
        HV2_OFF=0
        if command -v python3 >/dev/null 2>&1; then
        OFF=${'$'}(python3 -I - <<'CRB_PY'
        import json, os
        p = os.path.expanduser('~/.claude-remote/client-flags.json')
        try:
            # the same first 4096 bytes the client reads (`head -c 4096`): one verdict on both sides
            d = json.loads(open(p, 'rb').read(4096).decode('utf-8'))
        except FileNotFoundError:
            print('NONE'); raise SystemExit
        except Exception:
            print('BAD'); raise SystemExit
        o = d.get('off') if isinstance(d, dict) else 0
        if o is None:
            print('NONE')
        elif not isinstance(o, list):
            print('BAD')
        else:
            print(','.join(x for x in o if isinstance(x, str) and x.isidentifier()))
        CRB_PY
        )
        case ",${'$'}OFF," in *,hooks,*|*,pool,*|*,exec,*) HV2_OFF=1 ;; esac
        fi
    """.trimIndent()

    /** Only the flags (for [ServerBootstrap.refreshFlags]). */
    val FLAGS_ONLY_COMMAND: String = FLAGS_STEP + "\necho '$END'"

    /**
     * The whole bootstrap, in order: flags, the legacy Stop-hook ensure UNCHANGED,
     * hooks v2 (install only when [installV2] and the server does not force `hooks`
     * off, otherwise a read-only status), the streamd script, and whether `cr-session` ([SessionOpsScript], Jarvis
     * F0c) is in place. Only that check rides here: the ~25 KB installer goes in a second exec of its own, and only
     * when the check says the file is missing, damaged or older ([DefaultServerBootstrap.installSessionOps]).
     */
    fun build(installV2: Boolean): String = buildString {
        append(FLAGS_STEP).append('\n')
        append("echo '$HOOKS'\n").append(LEGACY_STOP_HOOK_COMMAND).append('\n')
        append("echo '$HOOKS_V2'\n")
        append(OFF_DECISION).append('\n')
        if (installV2) {
            append("if [ \"\$HV2_OFF\" = 1 ]; then echo HOOKS_V2_SKIPPED_OFF; else\n")
            append(HookCommands.ENSURE_HOOKS_V2_COMMAND).append("\nfi\n")
            append(HookCommands.STATUS_HOOKS_V2_COMMAND).append('\n')
        } else {
            append(HookCommands.STATUS_HOOKS_V2_COMMAND).append('\n')
        }
        append("echo '$STREAMD'\n").append(TranscriptService.ENSURE_STREAMD_COMMAND).append('\n')
        append("echo '$SESSIONOPS'\n").append(SessionOpsScript.CHECK_COMMAND).append('\n')
        append("echo '$END'\n")
    }

    data class Report(
        val flags: ClientFlags,
        /** Last line of the legacy Stop ensure (`HOOK_EXISTS|HOOK_FIXED|HOOK_FAILED`) or null. */
        val legacyHook: String?,
        val v2: HookCommands.V2Report,
        val streamd: String?,
        /** Last line of the `cr-session` check (`SESSIONOPS_PRESENT|NEEDED`) or null. Not part of [ok]. */
        val sessionOps: String? = null,
        /** The end marker arrived: the script ran to the end. */
        val complete: Boolean,
    ) {
        /**
         * The script ran to its end and produced the streamd line: worth remembering.
         * A step that reports its own failure (`HOOK_FAILED` on an unparseable
         * settings.json, `HOOKS_V2_FAILED`) is logged but not retried on a timer: the
         * next ensure (6 h, reconnect, restart) looks again, as today.
         */
        val ok: Boolean get() = complete && streamd != null
    }

    fun parse(output: String): Report {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var cur: MutableList<String>? = null
        var complete = false
        for (line in output.lines()) {
            when (line.trim()) {
                FLAGS -> cur = sections.getOrPut(FLAGS) { mutableListOf() }
                HOOKS -> cur = sections.getOrPut(HOOKS) { mutableListOf() }
                HOOKS_V2 -> cur = sections.getOrPut(HOOKS_V2) { mutableListOf() }
                STREAMD -> cur = sections.getOrPut(STREAMD) { mutableListOf() }
                SESSIONOPS -> cur = sections.getOrPut(SESSIONOPS) { mutableListOf() }
                END -> { complete = true; cur = null }
                else -> cur?.add(line)
            }
        }
        val flagsText = sections[FLAGS]?.joinToString("\n")?.trim()
        val hooksLast = sections[HOOKS]?.lastOrNull { it.isNotBlank() }?.trim()
        val v2Text = sections[HOOKS_V2]?.joinToString("\n").orEmpty()
        val streamdLast = sections[STREAMD]?.lastOrNull { it.isNotBlank() }?.trim()
        return Report(
            flags = if (sections.containsKey(FLAGS)) ClientFlags.parse(flagsText) else ClientFlags.Absent,
            legacyHook = hooksLast,
            v2 = HookCommands.parseV2(v2Text),
            streamd = streamdLast,
            sessionOps = sections[SESSIONOPS]?.lastOrNull { it.isNotBlank() }?.trim(),
            complete = complete,
        )
    }

    /** Flags only (from [FLAGS_ONLY_COMMAND] output). Null when the output is not from this script. */
    fun parseFlags(output: String): ClientFlags? {
        val r = parse(output)
        return if (r.complete) r.flags else null
    }
}

/**
 * Per-server bootstrap: one exec brings a server to the state the app expects
 * (client flags read, legacy Stop hook ensured, hooks v2 status read or, on request,
 * installed, streamd script in place) and is remembered.
 *
 *  - **Single flight** per server in the bootstrap's own scope: a caller that is
 *    cancelled does not cancel the work, and N callers cost one exec.
 *  - **Memo**: success is remembered for [REENSURE_MS] (6 h, which also repairs a
 *    settings.json rewritten by Claude or OMC on a long-running client);
 *    [invalidate] forgets it (watcher reconnect, streamd EOF without hello).
 *  - **Failure** (no exec, timeout, incomplete output, a step that failed): the
 *    next try is delayed 10 s, 20 s, ... up to 5 min; `ensure` during that time
 *    returns without doing anything. It never throws.
 *  - **Transport**: [exec] first; when it is not usable (anything but a timeout)
 *    [fallback], which is the per-tab session path, so a server whose exec
 *    transport is down is still bootstrapped.
 *  - **Force-off** (`client-flags.json`) is decided on the server, in the same
 *    script, before the install step; the client applies the same list to
 *    [CommFlags] ([applyServerOff]). A corrupt file switches nothing off.
 *  - **Hooks v2 install and removal happen only on request** ([installRequests] =
 *    [CommFlags.hookInstallRequests], [removeRequests]); every other run only reads
 *    their state. A request is consumed by the run that carries it.
 */
internal class DefaultServerBootstrap(
    private val scope: CoroutineScope,
    private val exec: RemoteExec,
    private val fallback: (suspend (serverId: String, cmd: String, totalMs: Long) -> String)? = null,
    private val serverName: (String) -> String = { it },
    private val applyServerOff: (String, Set<String>) -> Unit = { id, off -> CommFlags.applyServerOff(id, off) },
    installRequests: Flow<String> = CommFlags.hookInstallRequests,
    removeRequests: Flow<String> = HookInstallControl.shared.removeRequests,
    private val control: HookInstallControl = HookInstallControl.shared,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ServerBootstrap {

    private class State {
        var okAt: Long? = null
        var failures = 0
        var nextAttemptAt = 0L
        var inflight: Deferred<Unit>? = null
    }

    private val lock = Any()
    private val states = HashMap<String, State>()
    private val pendingInstall = HashSet<String>()

    init {
        scope.launch { installRequests.collect { onInstallRequest(it) } }
        scope.launch { removeRequests.collect { onRemoveRequest(it) } }
    }

    private fun stateOf(serverId: String): State = states.getOrPut(serverId) { State() }

    override suspend fun ensure(serverId: String) {
        val d = synchronized(lock) {
            val st = stateOf(serverId)
            st.inflight?.let { return@synchronized it }
            val now = clock()
            val okAt = st.okAt
            if (okAt != null && now - okAt < REENSURE_MS) return
            if (now < st.nextAttemptAt) return
            val job = scope.async(start = CoroutineStart.LAZY) {
                try {
                    runOnce(serverId)
                } finally {
                    synchronized(lock) { stateOf(serverId).inflight = null }
                }
            }
            st.inflight = job
            job
        }
        d.start()
        d.await()
    }

    override fun invalidate(serverId: String) {
        synchronized(lock) { stateOf(serverId).okAt = null }
    }

    override suspend fun refreshFlags(serverId: String) {
        val out = try {
            execText(serverId, BootstrapScript.FLAGS_ONLY_COMMAND, FLAGS_TIMEOUT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(TAG, "refreshFlags ${serverName(serverId)}: ${e.message}")
            return
        }
        BootstrapScript.parseFlags(out)?.let { applyFlags(serverId, it) }
    }

    private fun onInstallRequest(serverId: String) {
        // The effective flags decide, not the request: a server (or this device) that has hooks off
        // would never read the events, so writing into the Claude settings of every live session
        // would be all cost.
        HookInstallControl.installBlockedReason(CommFlags.effectiveFor(serverId))?.let { why ->
            FileLogger.log(TAG, "Hooks v2 install request for ${serverName(serverId)} dropped: $why")
            control.update(serverId) { it.copy(lastResult = HookCommands.V2Result.SKIPPED_OFF, atMs = clock(), busy = false) }
            return
        }
        synchronized(lock) { pendingInstall += serverId }
        control.update(serverId) { it.copy(busy = true) }
        scope.launch {
            // A run already in flight may have started before the request and so not carry
            // it; the second pass does. A request is taken at the start of a run, which is
            // why two passes are always enough.
            repeat(2) {
                synchronized(lock) {
                    val st = stateOf(serverId)
                    st.okAt = null
                    st.nextAttemptAt = 0L
                }
                ensure(serverId)
                if (synchronized(lock) { serverId !in pendingInstall }) return@launch
            }
            synchronized(lock) { pendingInstall -= serverId }
            control.update(serverId) { it.copy(busy = false) }
        }
    }

    private suspend fun onRemoveRequest(serverId: String) {
        control.update(serverId) { it.copy(busy = true) }
        val name = serverName(serverId)
        try {
            val out = execText(serverId, HookCommands.REMOVE_HOOKS_V2_COMMAND, REMOVE_TIMEOUT_MS)
            val r = HookCommands.parseV2(out)
            FileLogger.log(TAG, "Hooks v2 removal $name: ${r.result}")
            control.update(serverId) {
                it.copy(state = if (r.result == HookCommands.V2Result.REMOVED) HookCommands.V2State.ABSENT else it.state,
                    lastResult = r.result, atMs = clock(), busy = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.log(TAG, "Hooks v2 removal $name failed: ${e.message}")
            control.update(serverId) { it.copy(lastResult = HookCommands.V2Result.FAILED, atMs = clock(), busy = false) }
        }
    }

    private suspend fun runOnce(serverId: String) {
        val install = synchronized(lock) { pendingInstall.remove(serverId) }
        val t0 = clock()
        val name = serverName(serverId)
        val report = try {
            BootstrapScript.parse(execText(serverId, BootstrapScript.build(install), SCRIPT_TIMEOUT_MS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(serverId, "$name: ${e.message}")
            if (install) control.update(serverId) { it.copy(lastResult = HookCommands.V2Result.FAILED, atMs = clock(), busy = false) }
            return
        }
        // A truncated output must not read as "client-flags.json is corrupt" and lift a force-off.
        if (report.complete) applyFlags(serverId, report.flags)
        val sessionOps = if (report.sessionOps == SessionOpsScript.CHECK_NEEDED) installSessionOps(serverId) else report.sessionOps
        val v2 = report.v2
        control.update(serverId) {
            it.copy(
                state = report.v2.state,
                offSwitch = report.v2.offSwitch,
                lastResult = if (install) v2.result else it.lastResult,
                atMs = clock(),
                busy = false,
            )
        }
        val flagsLog = when (val f = report.flags) {
            ClientFlags.Absent -> "none"
            ClientFlags.Unparseable -> "unparseable"
            is ClientFlags.Off -> if (f.groups.isEmpty()) "none" else f.groups.sorted().joinToString(",")
        }
        FileLogger.log(
            TAG,
            "Server bootstrap $name: flags=$flagsLog hooks=${report.legacyHook ?: "?"} " +
                "hooksV2=${report.v2.state}${if (install) "/" + v2.result else ""} streamd=${report.streamd ?: "?"} " +
                "sessionOps=${sessionOps ?: "?"} (${clock() - t0}ms)",
        )
        if (report.ok) {
            synchronized(lock) {
                val st = stateOf(serverId)
                st.okAt = clock()
                st.failures = 0
                st.nextAttemptAt = 0L
            }
        } else {
            fail(serverId, "$name: incomplete (complete=${report.complete} hooks=${report.legacyHook} streamd=${report.streamd})")
        }
    }

    /**
     * The `cr-session` installer, sent only after the bootstrap's check said the file needs writing: almost every
     * bootstrap (6 h, reconnect, restart) then carries a few hundred bytes instead of the whole script, and the
     * critical bootstrap exec no longer waits on 25 KB going up a slow link. Its own exec, its own failure: nothing
     * runs `cr-session` on its own, so a failed install only waits for the next bootstrap's check and never fails
     * this one. The installer checks again before writing (another device may have installed meanwhile).
     */
    private suspend fun installSessionOps(serverId: String): String = try {
        // D3: the ~40 KB installer on stdin behind StdinScript.EXEC, paced, run only when it arrived whole. Without the
        // end marker it did not run (or its end went unseen): failed, the next bootstrap's check decides again. The
        // fallback (no exec transport) still sends it as the argument, as the bootstrap's own fallback does.
        val out = try {
            val res = exec.run(serverId, StdinScript.EXEC, StdinScript.stdin(SessionOpsScript.INSTALL_COMMAND),
                totalMs = INSTALL_TIMEOUT_MS, kind = ExecKind.SHORT, paceStdin = Pace())
            when (val done = StdinScript.parse(res.stdout)) {
                is StdinScript.Outcome.Completed -> done.out
                StdinScript.Outcome.Incomplete -> return "SESSIONOPS_FAILED (incomplete, exit ${res.exitStatus})"
            }
        } catch (e: ExecUnavailable) {
            val fb = fallback
            if (fb == null || e.reason == ExecUnavailableReason.TIMEOUT) throw e
            fb(serverId, SessionOpsScript.INSTALL_COMMAND, INSTALL_TIMEOUT_MS)
        }
        out.lines().lastOrNull { it.isNotBlank() }?.trim() ?: "SESSIONOPS_FAILED (no output)"
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "SESSIONOPS_FAILED (${e.message})"
    }

    private fun fail(serverId: String, why: String) {
        val wait = synchronized(lock) {
            val st = stateOf(serverId)
            st.failures++
            val w = (BACKOFF_MIN_MS shl (st.failures - 1).coerceAtMost(10)).coerceAtMost(BACKOFF_MAX_MS)
            st.nextAttemptAt = clock() + w
            w
        }
        FileLogger.log(TAG, "Server bootstrap failed ($why), next try in ${wait}ms")
    }

    private fun applyFlags(serverId: String, flags: ClientFlags) {
        when (flags) {
            ClientFlags.Absent -> applyServerOff(serverId, emptySet())
            ClientFlags.Unparseable -> {
                FileLogger.log(TAG, "CommFlags: client-flags.json unparseable (${serverName(serverId)}), nothing is switched off")
                applyServerOff(serverId, emptySet())
            }
            is ClientFlags.Off -> applyServerOff(serverId, flags.groups)
        }
    }

    private suspend fun execText(serverId: String, cmd: String, totalMs: Long): String {
        try {
            return exec.run(serverId, cmd, totalMs = totalMs, kind = ExecKind.SHORT).stdout
        } catch (e: ExecUnavailable) {
            val fb = fallback
            if (fb == null || e.reason == ExecUnavailableReason.TIMEOUT) throw e
            return fb(serverId, cmd, totalMs)
        }
    }

    companion object {
        const val REENSURE_MS = 6L * 60 * 60 * 1000
        const val BACKOFF_MIN_MS = 10_000L
        const val BACKOFF_MAX_MS = 5L * 60 * 1000
        private const val SCRIPT_TIMEOUT_MS = 30_000L
        private const val FLAGS_TIMEOUT_MS = 10_000L
        private const val REMOVE_TIMEOUT_MS = 20_000L
        private const val INSTALL_TIMEOUT_MS = 20_000L
        private const val TAG = "ServerBootstrap"
    }
}
