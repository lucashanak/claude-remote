package com.clauderemote.session

import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.service.ClosedRecord
import com.clauderemote.session.service.OpenedMarker
import com.clauderemote.session.service.RestoreResult
import com.clauderemote.storage.PersistedSession

/** One "Nedávno zavřené" row: a [ClosedRecord] of the server it was read from. */
internal data class ClosedEntry(val serverId: String, val serverName: String, val record: ClosedRecord) {
    val key: String get() = "$serverId\u0000${record.tmuxName}\u0000${record.at.orEmpty()}"
}

/** One server's read of `closed.jsonl`. */
internal sealed interface ClosedRead {
    /** Newest first. */
    data class Ok(val records: List<ClosedRecord>) : ClosedRead
    /** No answer, or a read cut off: unknown, never "nothing closed". */
    data object Unknown : ClosedRead
    /** Not read: the server has no transport and the caller would not pay a handshake for it. */
    data object Skipped : ClosedRead
}

/** Who closed a session, as the row says it (§3.9): read from the record's `by`. */
internal enum class ClosedBy { ME, OTHER_DEVICE, JARVIS, PRUNE, APP, UNKNOWN }

/** What the UI does with a [RestoreResult] (Jarvis F0b): every result has its own reaction, none is silent. */
internal sealed interface RestoreAction {
    /** The session runs: open its tab. [loginRequired] = it waits for a login, say so. */
    data class OpenTab(val loginRequired: Boolean) : RestoreAction
    /** The name is taken by another live session: one tap restores under [suggestedName]. */
    data class OfferName(val suggestedName: String) : RestoreAction
    /** The conversation already runs in [tmuxName] (null = it does, but where is unknown). */
    data class GoTo(val tmuxName: String?) : RestoreAction
    /** No transcript: offer the [candidate] conversation (to be confirmed) and a new session in the same folder and account. */
    data class OfferCandidate(val candidate: String?) : RestoreAction
    /** Nothing was started (or nobody knows): tell the user why. */
    data class Message(val title: String, val text: String) : RestoreAction
}

/** What Ctrl/Cmd+Shift+T does. */
internal sealed interface ReopenTarget {
    /** A close still in its undo window: undo it ("Vrátit"). */
    data class Undo(val sessionId: String) : ReopenTarget
    data class Restore(val entry: ClosedEntry) : ReopenTarget
    data object Nothing : ReopenTarget
}

/** Pure rules of "Nedávno zavřené" and of the restore (§3.9, §15.13, §15.14). */
internal object RecentlyClosed {

    /** How far back the section looks: durable tombstones hold 14 days, an older close has nothing to undo (§3.9). */
    const val MAX_AGE_S = 14L * 24 * 3600

    /** Records read per server (the tail of `closed.jsonl`). */
    const val READ_LIMIT = 50

    /** The restore waits up to [com.clauderemote.session.service.SessionRestore.APP_WAIT_S] for claude: the exec gets much more. */
    const val RESTORE_EXEC_MS = 25_000L

    /** `by` → who closed it. [ownBy] is this device's own `app:<installId>`. */
    fun closedBy(by: String?, ownBy: String): ClosedBy = when {
        by.isNullOrBlank() -> ClosedBy.UNKNOWN
        by == ownBy -> ClosedBy.ME
        by == "jarvis" -> ClosedBy.JARVIS
        by == "prune" -> ClosedBy.PRUNE
        // a build that did not know its install id yet writes plain "app"
        by == "app" || by == "app:" -> ClosedBy.APP
        by.startsWith("app:") -> ClosedBy.OTHER_DEVICE
        else -> ClosedBy.UNKNOWN
    }

    fun closedByLabel(kind: ClosedBy): String = when (kind) {
        ClosedBy.ME -> "zavřeno tady"
        ClosedBy.OTHER_DEVICE -> "zavřelo jiné zařízení"
        ClosedBy.JARVIS -> "zavřel Jarvis"
        // the app's dead-pane prune guessed the pane was gone: not a person's choice, the row must say so (§15.14)
        ClosedBy.PRUNE -> "zavřela automatika"
        ClosedBy.APP -> "zavřela aplikace"
        ClosedBy.UNKNOWN -> ""
    }

    /** The row's "who and why": `zavřel Jarvis: hotovo`. */
    fun closedByLine(record: ClosedRecord, ownBy: String): String {
        val who = closedByLabel(closedBy(record.by, ownBy))
        val why = record.reason?.takeIf { it.isNotBlank() }
        return when {
            who.isEmpty() -> why.orEmpty()
            why == null -> who
            else -> "$who: $why"
        }
    }

    /** The row's name: the session's alias, else the leaf of its folder. */
    fun label(record: ClosedRecord, serverName: String): String {
        val parsed = TmuxNameParser.parse(record.tmuxName, serverName)
        if (parsed.alias.isNotBlank()) return parsed.alias
        return folderLeaf(record.restoreFolder) ?: parsed.folder
    }

    fun folderLeaf(folder: String?): String? =
        folder?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** `právě teď`, `před 12 min`, `před 3 h`, `včera 18:40`, `před 4 dny`; null when the time is unknown. */
    fun whenLabel(atEpochS: Long?, nowEpochS: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
        val at = atEpochS ?: return null
        val d = (nowEpochS - at).coerceAtLeast(0)
        if (d < 60) return "právě teď"
        if (d < 3600) return "před ${d / 60} min"
        val atDay = java.time.Instant.ofEpochSecond(at).atZone(zone)
        val nowDay = java.time.Instant.ofEpochSecond(nowEpochS).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(atDay.toLocalDate(), nowDay)
        return when {
            days <= 0L -> "před ${d / 3600} h"
            days == 1L -> "včera %02d:%02d".format(atDay.hour, atDay.minute)
            else -> "před $days dny"
        }
    }

    /** The record can be resumed (a folder and a conversation id to resume). */
    fun canResume(record: ClosedRecord): Boolean =
        record.restoreFolder != null && (record.sessionId ?: record.launchSessionId)?.let { UUIDISH.matches(it) } == true

    /** A new conversation in the same folder and account is possible. */
    fun canStartFresh(record: ClosedRecord): Boolean = record.restoreFolder != null

    private val UUIDISH = Regex("^[0-9a-f-]{8,64}$")

    /**
     * The rows of the section, newest first: per server and tmux name one close — the newest that can be restored,
     * else the newest (an empty record, written by an older build over a pane that was already gone, must not hide
     * the real one before it); nothing older than [MAX_AGE_S]; nothing whose name is [live] now (it runs, it is in
     * the session list, a restore would only say "taken"). [perServer] holds what each server answered; a null read
     * is not a server with nothing closed. `cr-session restore` picks the same way.
     */
    fun merge(
        perServer: List<Pair<Pair<String, String>, List<ClosedRecord>>>,
        live: Set<Pair<String, String>>,
        nowEpochS: Long,
        limit: Int = 20,
    ): List<ClosedEntry> {
        val all = perServer.flatMap { (srv, records) -> records.map { ClosedEntry(srv.first, srv.second, it) } }
        return all
            .filter { e -> e.record.atEpochSeconds?.let { nowEpochS - it <= MAX_AGE_S } ?: true }
            .sortedByDescending { it.record.atEpochSeconds ?: Long.MIN_VALUE }
            .groupBy { it.serverId to it.record.tmuxName }
            .values
            .map { same -> same.firstOrNull { canStartFresh(it.record) } ?: same.first() }
            .sortedByDescending { it.record.atEpochSeconds ?: Long.MIN_VALUE }
            .filter { (it.serverId to it.record.tmuxName) !in live }
            .take(limit)
    }

    /**
     * Ctrl/Cmd+Shift+T: a close still inside its undo window wins (it has no record yet, and undoing it is free:
     * the pane never died); else the newest row that can be restored and that THIS device closed ([ownBy]): the
     * shortcut undoes my own close, another device's or Jarvis's closes are restored from the list only.
     * [pendingInOrder] = PendingCloses' pending ids in the order that state lists them (newest LAST).
     */
    fun reopenTarget(pendingInOrder: List<String>, entries: List<ClosedEntry>, ownBy: String): ReopenTarget {
        pendingInOrder.lastOrNull()?.let { return ReopenTarget.Undo(it) }
        return entries.firstOrNull { it.record.by == ownBy && (canResume(it.record) || canStartFresh(it.record)) }
            ?.let { ReopenTarget.Restore(it) }
            ?: ReopenTarget.Nothing
    }

    /**
     * "Přejít na ni" is offered only for the app's own sessions (the script names one on its own tmux socket only, and
     * `cr-session` knows only the box `server`'s prefix).
     */
    fun canGoTo(tmuxName: String): Boolean = tmuxName.startsWith(com.clauderemote.model.TmuxNameParser.DEFAULT_PREFIX)

    /** The UI's reaction to [result] of restoring [record]. */
    fun actionFor(result: RestoreResult, record: ClosedRecord, serverName: String): RestoreAction {
        val name = label(record, serverName)
        return when (result) {
            is RestoreResult.Restored -> RestoreAction.OpenTab(loginRequired = false)
            RestoreResult.LoginRequired -> RestoreAction.OpenTab(loginRequired = true)
            is RestoreResult.Exists -> result.suggestedName?.takeIf { it.isNotBlank() }?.let { RestoreAction.OfferName(it) }
                ?: RestoreAction.Message("Jméno je obsazené", "Jméno „$name“ je obsazené a volné náhradní jméno se nenašlo.")
            RestoreResult.PendingSelfHeal -> RestoreAction.Message(
                "Čeká na self-heal",
                "Server ještě drží „$name“ v manifestu a do minuty ji sám spustí. Zkus to za minutu.",
            )
            // only one of ours can be switched to: a tab on another name would push it into the manifest, and a
            // later close of that tab would kill a session that was never the app's
            is RestoreResult.RunningElsewhere -> RestoreAction.GoTo(result.tmuxName?.takeIf { canGoTo(it) })
            is RestoreResult.NoTranscript -> RestoreAction.OfferCandidate(result.candidateSessionId)
            is RestoreResult.LoggedOut -> RestoreAction.Message(
                "Účet není přihlášený",
                "Účet ${result.account} na serveru $serverName není přihlášený. Přihlas ho v Účtech a zkus obnovu znovu.",
            )
            is RestoreResult.Refused -> RestoreAction.Message("Obnova odmítnuta", "Server obnovu „$name“ odmítl: ${result.why}.")
            is RestoreResult.Failed -> RestoreAction.Message(
                "Obnova selhala",
                "Obnova „$name“ selhala" + (result.detail.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ".",
            )
            // the session may have started: say so, never retry on our own (a second try could start a second one)
            RestoreResult.Unknown -> RestoreAction.Message(
                "Výsledek nedorazil",
                "Odpověď serveru na obnovu „$name“ nedorazila. Session se možná spustila: podívej se do seznamu sessions, než to zkusíš znovu.",
            )
        }
    }
}

/** A marker of `opened.d` this device should adopt as a parked tab, with the manifest entry it is built from. */
internal data class Adoption(val marker: OpenedMarker, val entry: PersistedSession)

/**
 * Which `opened.d` markers become parked tabs here (§15.4). The marker says THAT someone else opened or restored the
 * session; what the tab holds (folder, mode, model, account, conversation) comes from the server's manifest entry,
 * which drift writes from the live process within a minute. Copying the manifest is what keeps the later
 * `pushSessionsToServer` harmless (pushMerge: incoming wins) — a guessed mode or model would overwrite the truth.
 */
internal object OpenedAdoption {
    const val MAX_AGE_S = com.clauderemote.session.service.ManifestCommands.OPENED_MAX_AGE_S

    /**
     * Unknown (null) markers, live names or manifest = nothing. A marker is adopted when it is younger than
     * [MAX_AGE_S], its name is live, not tombstoned, not a tab or a launch/restore of this device ([ownNames]),
     * not suppressed by [hidden] (hidden or closed here after the marker was written), and the manifest has it.
     */
    fun select(
        serverId: String,
        markers: List<OpenedMarker>?,
        liveNames: Set<String>?,
        manifest: List<PersistedSession>?,
        tombstoned: Set<String>?,
        ownNames: Set<String>,
        hidden: HiddenAdoptions,
        nowEpochS: Long,
    ): List<Adoption> {
        if (markers == null || liveNames == null || manifest == null || tombstoned == null) return emptyList()
        return markers
            .filter { nowEpochS - it.mtimeEpochS < MAX_AGE_S }
            .filter { it.tmuxName in liveNames && it.tmuxName !in tombstoned && it.tmuxName !in ownNames }
            .filter { !hidden.isSuppressed(serverId, it.tmuxName, it.mtimeEpochS) }
            .distinctBy { it.tmuxName }
            .mapNotNull { m -> manifest.firstOrNull { it.tmuxSessionName == m.tmuxName }?.let { Adoption(m, it) } }
    }

    /** The badge of an adopted tab. */
    fun badge(by: String?): String = when (by) {
        "jarvis" -> "od Jarvise"
        "restore" -> "obnoveno"
        null, "" -> "přidáno"
        else -> "od: $by"
    }
}

/**
 * This device's "do not adopt" list: (server, tmux name) → a stamp (epoch s). A marker is suppressed while its mtime
 * is not newer than the stamp, so hiding (or closing here) wins over the marker that was there, and a LATER open or
 * restore of the name (a rewritten marker) is a new event that may be adopted again. Entries older than [KEEP_S]
 * are dropped: a marker is deleted after 7 days on the server anyway.
 */
internal class HiddenAdoptions private constructor(private val stamps: Map<Pair<String, String>, Long>) {

    fun isSuppressed(serverId: String, tmuxName: String, markerMtimeEpochS: Long): Boolean =
        stamps[serverId to tmuxName]?.let { markerMtimeEpochS <= it } ?: false

    fun with(serverId: String, tmuxName: String, stampEpochS: Long): HiddenAdoptions {
        val k = serverId to tmuxName
        return HiddenAdoptions(stamps + (k to maxOf(stampEpochS, stamps[k] ?: Long.MIN_VALUE)))
    }

    fun pruned(nowEpochS: Long): HiddenAdoptions = HiddenAdoptions(stamps.filterValues { nowEpochS - it <= KEEP_S })

    val size: Int get() = stamps.size

    fun serialize(): String = stamps.entries.joinToString("\n") { (k, v) -> "${k.first}\t${k.second}\t$v" }

    companion object {
        const val KEEP_S = 8L * 24 * 3600
        val EMPTY = HiddenAdoptions(emptyMap())

        /** Lines `serverId<TAB>tmuxName<TAB>stamp`; a line that does not read is skipped. */
        fun parse(s: String?): HiddenAdoptions {
            if (s.isNullOrBlank()) return EMPTY
            val m = HashMap<Pair<String, String>, Long>()
            for (line in s.lineSequence()) {
                val t = line.split('\t')
                if (t.size != 3 || t[0].isEmpty() || t[1].isEmpty()) continue
                val v = t[2].toLongOrNull() ?: continue
                val k = t[0] to t[1]
                m[k] = maxOf(v, m[k] ?: Long.MIN_VALUE)
            }
            return HiddenAdoptions(m)
        }

        /**
         * The stamp of a hide at [nowEpochS] (device clock) of a name whose marker had [knownMarkerMtime] (server
         * clock): the larger one, so a server clock ahead of the device cannot bring the hidden tab back.
         */
        fun stamp(nowEpochS: Long, knownMarkerMtime: Long?): Long = maxOf(nowEpochS, knownMarkerMtime ?: Long.MIN_VALUE)
    }
}

/** Where [HiddenAdoptions] is kept (AppSettings; a seam so the orchestrator holds no settings object). */
internal interface HiddenAdoptionsStore {
    fun load(): String
    fun save(value: String)
}
