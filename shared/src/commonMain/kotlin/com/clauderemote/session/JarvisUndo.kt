package com.clauderemote.session

import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.JarvisAlert
import com.clauderemote.session.service.JarvisAlertKind
import com.clauderemote.session.service.RestoreResult

/** "Vrátit" of a close Jarvis announced: [tmux] on [serverId] (the alert's tmux field and server). */
data class JarvisUndoRequest(val serverId: String, val tmux: String)

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
     * Picks the record to restore from [records] (newest first, as [SessionOrchestrator.readClosedRecords] returns
     * them). Only the NEWEST record of [tmux] counts: if somebody restored it and closed it again since, the undo of
     * Jarvis' close is gone and "Vrátit" must not bring back a session somebody else closed on purpose.
     */
    internal fun pick(records: List<ClosedRecord>, tmux: String, nowEpochS: Long): Pick {
        val name = alias(tmux)
        val r = records.firstOrNull { it.tmuxName == tmux }
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
    ): String {
        val records = (read() as? ClosedRead.Ok)?.records
            ?: return "Nedávno zavřené na $serverName nejde načíst – zkus to za chvíli."
        val found = when (val p = pick(records, tmux, nowEpochS)) {
            is Pick.No -> return p.why
            is Pick.Found -> p
        }
        val result = restore(found.record, if (found.fresh) newId() else null)
        return headlessText(RecentlyClosed.actionFor(result, found.record, serverName), alias(tmux))
    }

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
