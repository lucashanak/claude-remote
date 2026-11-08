package com.clauderemote.session

import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import com.clauderemote.session.service.RestoreResult

/** "Vrátit" of a close Jarvis announced: [tmux] on [serverId] (the alert's tmux field and server). */
data class JarvisUndoRequest(val serverId: String, val tmux: String)

/**
 * The undos under way, by (server id, tmux name) — ONE set for every way in (the phone notification, the Velín's
 * news line, the watch): two taps, or a tap on the wrist and one on the phone, must not start two restores (with a
 * new conversation id each, both could start; rev-f4-app MED-1). [state] drives the disabled "Vracím…" button.
 */
class JarvisUndoInFlight {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    val state: kotlinx.coroutines.flow.StateFlow<Set<Pair<String, String>>> = _state

    /** False = already under way; the caller does nothing (and says so). Atomic: a CAS on the set. */
    fun tryEnter(serverId: String, tmux: String): Boolean {
        val k = serverId to tmux
        while (true) {
            val cur = _state.value
            if (k in cur) return false
            if (_state.compareAndSet(cur, cur + k)) return true
        }
    }

    fun leave(serverId: String, tmux: String) {
        val k = serverId to tmux
        while (true) {
            val cur = _state.value
            if (_state.compareAndSet(cur, cur - k)) return
        }
    }

    /** [block] while holding (server, name); null without running it when that undo is already under way. */
    suspend fun <T> run(serverId: String, tmux: String, block: suspend () -> T): T? {
        if (!tryEnter(serverId, tmux)) return null
        try {
            return block()
        } finally {
            leave(serverId, tmux)
        }
    }

    fun isRunning(serverId: String, tmux: String): Boolean = (serverId to tmux) in _state.value
}

/**
 * Jarvis F4: undoing a close Jarvis did. His alert stays in the F3 format (contract: kind `done`, text
 * "Zavřel jsem <alias>.", the tmux field = the closed name, no new field — an older app would drop a 7-field line);
 * whether it can still be undone is decided by `closed.jsonl`, never by the alert: the newest record of that name
 * must be Jarvis' and younger than [WINDOW_S]. The undo itself is the F0 restore ("Nedávno zavřené"), nothing else.
 * Public for the phone and watch notifiers (another module); what touches the records stays internal.
 */
object JarvisUndo {
    /** The contract's undo window: a Jarvis close older than this is restored from the list like any other. */
    const val WINDOW_S = 24L * 3600

    const val CLOSE_PREFIX = "Zavřel jsem "

    /** At most this many "Vrátit" actions on one notification (Android shows three actions; one stays free). */
    const val MAX_ACTIONS = 2

    /** The Intent action of the phone notification's "Vrátit" (MainActivity takes only this action's extras). */
    const val INTENT_ACTION = "com.clauderemote.JARVIS_UNDO"

    /** How long a headless undo (the watch) may take before it is said not to have answered (rev-f4-app LOW). */
    const val HEADLESS_TIMEOUT_MS = 60_000L

    /**
     * The request an Intent carries: only with [INTENT_ACTION] and both extras (rev-f4-app LOW: another intent that
     * happens to carry the extras — a launcher shortcut, a share — is not "Vrátit").
     */
    fun requestFrom(action: String?, serverId: String?, tmux: String?): JarvisUndoRequest? =
        if (action == INTENT_ACTION && !serverId.isNullOrBlank() && !tmux.isNullOrBlank()) JarvisUndoRequest(serverId, tmux) else null

    /**
     * The Velín's button for [a]: null = no button (not a close), else whether it may be pressed — not while that
     * undo runs anywhere ([inFlight] = [JarvisUndoInFlight.state]; MED-1).
     */
    fun undoButtonEnabled(a: JarvisAlert, inFlight: Set<Pair<String, String>>): Boolean? =
        if (!isCloseAlert(a)) null else (a.serverId to a.tmux!!) !in inFlight

    /** Said when the same undo is already under way. */
    fun busyText(tmux: String): String = "„${alias(tmux)}“ už vracím."

    /** The alert announces a close by Jarvis: the only kind of alert that gets "Vrátit". */
    fun isCloseAlert(a: JarvisAlert): Boolean =
        a.kind == JarvisAlertKind.DONE && !a.tmux.isNullOrBlank() && a.text.startsWith(CLOSE_PREFIX)

    /** The close alerts among [pending] (newest first, as the feed holds them), one per session, at most [max]. */
    fun undoable(pending: List<JarvisAlert>, max: Int = MAX_ACTIONS): List<JarvisAlert> =
        pending.filter(::isCloseAlert).distinctBy { it.serverId to it.tmux }.take(max)

    /** `claude-server-kontexta--soniox` → `soniox`: the alias the action's label names. */
    fun alias(tmux: String): String = tmux.substringAfterLast("--").ifBlank { tmux }

    internal sealed interface Pick {
        /** [record] is Jarvis' close of the name, inside the window, and restorable ([fresh] = no conversation to resume). */
        data class Found(val record: ClosedRecord, val fresh: Boolean) : Pick
        /** Nothing to undo: [why] is the Czech sentence the user is shown. */
        data class No(val why: String) : Pick
    }

    /**
     * Picks the record to restore from [records]. Only the NEWEST record of [tmux] counts: if somebody restored it and
     * closed it again since, the undo of Jarvis' close is gone and "Vrátit" must not bring back a session somebody
     * else closed on purpose. Newest by its own time, not by its place in the list (rev-f4-app LOW-1); a record
     * without a time might be the newest, so it sorts first and nothing is undone ("nevím, kdy"). Equal times keep
     * the list's order ([SessionOrchestrator.readClosedRecords] gives newest first).
     */
    internal fun pick(records: List<ClosedRecord>, tmux: String, nowEpochS: Long): Pick {
        val name = alias(tmux)
        val r = records.filter { it.tmuxName == tmux }.sortedByDescending { it.atEpochSeconds ?: Long.MAX_VALUE }.firstOrNull()
            ?: return Pick.No("Záznam o zavření „$name“ jsem nenašel.")
        if (r.by != "jarvis") return Pick.No("„$name“ od té doby zavřel někdo jiný – obnov ji z Nedávno zavřených.")
        val at = r.atEpochSeconds ?: return Pick.No("Nevím, kdy se „$name“ zavřela – obnov ji z Nedávno zavřených.")
        if (nowEpochS - at > WINDOW_S) return Pick.No("Zavření „$name“ je starší než 24 h – obnov ji z Nedávno zavřených.")
        return when {
            RecentlyClosed.canResume(r) -> Pick.Found(r, fresh = false)
            RecentlyClosed.canStartFresh(r) -> Pick.Found(r, fresh = true)
            else -> Pick.No("„$name“ nejde obnovit: záznam nemá složku.")
        }
    }

    /**
     * The headless undo (the watch's "Vrátit"): [read] the server's closed.jsonl, [pick], then [restore] the picked
     * record under its own name — with a new conversation id from [newId] when it has none to resume, the same choice
     * a tap on the "Nedávno zavřené" row makes. [restore] is [SessionOrchestrator.restoreClosed]; the seams keep the
     * choice of record testable without a server. Returns the Czech sentence for the user.
     */
    internal suspend fun undo(
        tmux: String,
        serverName: String,
        nowEpochS: Long,
        read: suspend () -> ClosedRead,
        restore: suspend (record: ClosedRecord, freshSessionId: String?) -> RestoreResult,
        newId: () -> String,
        timeoutMs: Long = HEADLESS_TIMEOUT_MS,
    ): String = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        val records = (read() as? ClosedRead.Ok)?.records
            ?: return@withTimeoutOrNull "Nedávno zavřené na $serverName nejde načíst – zkus to za chvíli."
        val found = when (val p = pick(records, tmux, nowEpochS)) {
            is Pick.No -> return@withTimeoutOrNull p.why
            is Pick.Found -> p
        }
        val result = restore(found.record, if (found.fresh) newId() else null)
        headlessText(RecentlyClosed.actionFor(result, found.record, serverName), alias(tmux))
    } // the restore may have started: say so, never try again on our own
        ?: "Vracení „${alias(tmux)}“ neodpovědělo včas. Session se možná spustila: podívej se do seznamu sessions."

    /**
     * One sentence for an undo done without a screen (from the watch): what [action] means for [name]. Anything
     * that needs a choice (another name, another conversation) is NOT done headless — it is left to the app.
     */
    internal fun headlessText(action: RestoreAction, name: String): String = when (action) {
        is RestoreAction.OpenTab -> if (action.loginRequired) "„$name“ zase běží, ale chce přihlášení." else "Vrátil jsem „$name“."
        is RestoreAction.OfferName -> "Jméno „$name“ je obsazené – dokonči to v aplikaci (Nedávno zavřené)."
        is RestoreAction.GoTo -> "Konverzace „$name“ už běží" + (action.tmuxName?.let { " v „${alias(it)}“" } ?: "") + "."
        is RestoreAction.OfferCandidate -> "Transcript „$name“ chybí – dokonči to v aplikaci (Nedávno zavřené)."
        is RestoreAction.Message -> action.text
    }
}
