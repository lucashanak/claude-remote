package com.clauderemote.ui

import com.clauderemote.model.ClaudeSession
import com.clauderemote.model.RemoteSession
import com.clauderemote.model.SessionActivity
import com.clauderemote.model.SessionStatus
import com.clauderemote.model.TmuxNameParser
import com.clauderemote.session.service.PromptFingerprint
import com.clauderemote.session.service.PromptKind

/**
 * The Velín's decisions, without Compose: which state a session is in, how a project rolls up,
 * what waits for the user and in which order, and which permission is risky. Everything the
 * screen shows comes from data the app already has (hook reducer, prompt tokens, transcript,
 * tmux listing); nothing here reads the server.
 */

/**
 * Session states of the Velín (vocabulary of 50-ui.md §2.1): a glyph and a word for each, so the
 * state never rests on colour alone. [rank] orders a project: what waits for the user first,
 * then what broke, then what runs, then what rests. "Zaseklé" (⧗) needs Jarvis' `status.json`
 * and comes with F1; [OFFLINE] is the app's own "tab not connected" and has no hook state.
 */
internal enum class VelinState(val glyph: String, val label: String, val rank: Int) {
    APPROVAL("▲", "povolení", 0),
    QUESTION("✎", "otázka", 1),
    CRASHED("✕", "spadlé", 2),
    WORKING("▶", "pracuje", 3),
    READY("✓", "hotovo", 4),
    OFFLINE("○", "odpojeno", 5),
    IDLE("•", "nečinné", 6),
    ;

    val waitsForUser: Boolean get() = this == APPROVAL || this == QUESTION
}

/** One session row of the overview: an open tab ([sessionId] set) or a tmux session not open here. */
internal data class VelinRow(
    /** Unique in the list: the tab id, or `remote:<serverId>:<tmux>`. */
    val key: String,
    val sessionId: String?,
    val serverId: String,
    /** Folder leaf, lowercased: the project key. */
    val projectKey: String,
    /** Folder leaf as written, for the header. */
    val projectLabel: String,
    val title: String,
    val state: VelinState,
    /** When the session entered [state] (device ms), null when unknown. */
    val sinceMs: Long?,
    /** Account slug of the session (null = default login, unknown for a remote one). */
    val accountKey: String? = null,
)

internal data class VelinProject(
    val key: String,
    val label: String,
    val rows: List<VelinRow>,
) {
    val rollup: List<Pair<VelinState, Int>> get() = Velin.rollup(rows)
    val bestRank: Int get() = rows.minOfOrNull { it.state.rank } ?: Int.MAX_VALUE
}

internal enum class InboxKind {
    /** A tool permission dialog (hook `perm`, or a selector the screen saw). Ano/Ne only. */
    PERMISSION,
    /** An AskUserQuestion (hook `ask`). Answered with text, like the alert's "Odpovědět". */
    QUESTION,
    /** A finished turn whose last message ends with a question (from the transcript). Text answer. */
    STOP_QUESTION,
}

internal data class InboxItem(
    val sessionId: String,
    val title: String,
    val kind: InboxKind,
    /** What is asked, as the event or the transcript put it; blank when nothing named it. */
    val text: String,
    val sinceMs: Long?,
    /** The prompt a reply is checked against (ReplyGuard); null = nothing can be sent from here. */
    val prompt: PromptFingerprint?,
    /** For a [InboxKind.PERMISSION]: whether only a hold may approve it. */
    val risk: PermRisk.Verdict? = null,
) {
    /** The prompt's key when there is one: the identity "answered here" and "gone" are tracked by. */
    val key: String get() = prompt?.key ?: "$sessionId#${kind.name}"
}

/** How the Velín lets a [InboxItem] be approved ([Velin.approvalMode]). */
internal enum class ApprovalMode {
    /** No Ano/Ne here: not a permission, or nothing to check a reply against (answer in the app). */
    NONE,
    /** A plain tap: a permission whose command is known and not risky. */
    TAP,
    /** Only a hold approves: risky, or nobody knows what it runs. */
    HOLD,
}

internal object Velin {
    /**
     * Tap or hold for the "Ano" of [item]. A tap only for a permission whose risk was classified and found harmless;
     * a risky one and one with no verdict at all (risk unknown) need the hold — a missing verdict must never fall
     * through to the one-tap approval. The screen draws exactly what this says.
     */
    fun approvalMode(item: InboxItem): ApprovalMode = when {
        item.prompt == null || item.kind != InboxKind.PERMISSION -> ApprovalMode.NONE
        item.risk?.risky == false -> ApprovalMode.TAP
        else -> ApprovalMode.HOLD
    }

    /**
     * A `/clear` is an `end` followed by a `start` within milliseconds: the session counts as
     * crashed only once the `end` has stood alone this long, so the row does not flash.
     */
    const val CRASH_GRACE_MS = 5_000L

    fun isCrashed(endedAtMs: Long?, nowMs: Long): Boolean = endedAtMs != null && nowMs - endedAtMs >= CRASH_GRACE_MS

    /**
     * [connected]: the tab's transport is up. [promptKind]: the kind of the session's newest
     * announced prompt (tells a question from a permission). [crashed]: see [isCrashed].
     */
    fun stateOf(connected: Boolean, activity: SessionActivity?, promptKind: PromptKind?, crashed: Boolean): VelinState = when {
        !connected -> VelinState.OFFLINE
        // `end` clears a pending dialog in the reducer too, so a crashed session waits for nobody.
        crashed -> VelinState.CRASHED
        activity == SessionActivity.APPROVAL_NEEDED ->
            if (promptKind == PromptKind.QUESTION) VelinState.QUESTION else VelinState.APPROVAL
        activity == SessionActivity.WORKING -> VelinState.WORKING
        activity == SessionActivity.WAITING_FOR_INPUT -> VelinState.READY
        activity == SessionActivity.DISCONNECTED -> VelinState.OFFLINE
        else -> VelinState.IDLE
    }

    /**
     * The overview's rows: every tab, plus each tmux session of [remotes] that no tab shows (as
     * idle, with tmux's last activity: the app has no hook state for a session it does not watch).
     */
    fun rowsOf(
        tabs: List<ClaudeSession>,
        remotes: List<RemoteSession>,
        nowMs: Long,
        activityOf: (String) -> SessionActivity?,
        promptKindOf: (String) -> PromptKind?,
        endedAtOf: (String) -> Long?,
        sinceOf: (String) -> Long?,
    ): List<VelinRow> {
        val open = tabs.mapTo(HashSet()) { it.server.id + "\u0000" + it.tmuxSessionName }
        val tabRows = tabs.map { t ->
            val leaf = t.folder.trimEnd('/').substringAfterLast('/').ifBlank { t.folder }
            VelinRow(
                key = t.id, sessionId = t.id, serverId = t.server.id,
                projectKey = leaf.lowercase(), projectLabel = leaf,
                title = t.displayLabel,
                state = stateOf(
                    connected = t.status == SessionStatus.ACTIVE,
                    activity = activityOf(t.id),
                    promptKind = promptKindOf(t.id),
                    crashed = isCrashed(endedAtOf(t.id), nowMs),
                ),
                sinceMs = sinceOf(t.id),
                accountKey = t.accountKey,
            )
        }
        val remoteRows = remotes
            .filter { (it.server.id + "\u0000" + it.tmuxSession.name) !in open }
            .distinctBy { it.server.id + "\u0000" + it.tmuxSession.name }
            .map { r ->
                val parsed = TmuxNameParser.parse(r.tmuxSession.name, r.server.name)
                val leaf = parsed.folder.trimEnd('/').substringAfterLast('/').ifBlank { parsed.folder }
                VelinRow(
                    key = "remote:${r.server.id}:${r.tmuxSession.name}", sessionId = null, serverId = r.server.id,
                    projectKey = leaf.lowercase(), projectLabel = leaf,
                    title = parsed.alias.ifBlank { leaf },
                    state = VelinState.IDLE,
                    sinceMs = r.tmuxSession.lastActivity.takeIf { it > 0 }?.let { it * 1000 },
                )
            }
        return tabRows + remoteRows
    }

    /** Non-zero counts in [VelinState] order. */
    fun rollup(rows: List<VelinRow>): List<Pair<VelinState, Int>> {
        val counts = rows.groupingBy { it.state }.eachCount()
        return VelinState.entries.mapNotNull { s -> counts[s]?.let { s to it } }
    }

    /** Within a project: by state rank, then the longest in its state first, then the title. */
    val rowOrder: Comparator<VelinRow> = compareBy<VelinRow>({ it.state.rank }, { it.sinceMs ?: Long.MAX_VALUE }, { it.title.lowercase() }, { it.key })

    /**
     * Sessions grouped by folder leaf (the project). Projects with something waiting come first,
     * then by label; every row stays (nothing is hidden here, the screen collapses idle rows).
     */
    fun projects(rows: List<VelinRow>): List<VelinProject> = rows
        .groupBy { it.projectKey }
        .map { (key, rs) -> VelinProject(key, rs.first().projectLabel.ifBlank { key }, rs.sortedWith(rowOrder)) }
        .sortedWith(compareBy({ it.bestRank }, { it.label.lowercase() }))

    /** "<1 min", "12 min", "3 h", "2 d"; blank when unknown. */
    fun formatAge(sinceMs: Long?, nowMs: Long): String {
        if (sinceMs == null) return ""
        val d = (nowMs - sinceMs).coerceAtLeast(0)
        return when {
            d < 60_000 -> "<1 min"
            d < 3_600_000 -> "${d / 60_000} min"
            d < 86_400_000 -> "${d / 3_600_000} h"
            else -> "${d / 86_400_000} d"
        }
    }

    /** Permissions before questions before finished turns; within a kind the oldest first. */
    val inboxOrder: Comparator<InboxItem> = compareBy<InboxItem>({ it.kind.ordinal }, { it.sinceMs ?: Long.MAX_VALUE }, { it.title.lowercase() }, { it.sessionId })

    fun sortInbox(items: List<InboxItem>): List<InboxItem> = items.sortedWith(inboxOrder)

    /**
     * The inbox entry of one session, or null when it waits for nothing.
     * [promptKind]/[promptText]/[promptAtMs]/[prompt]: the session's newest announced prompt
     * (PromptTokens). [hookNamed]: a hook event names that dialog, so the server can verify a
     * reply; a dialog only the screen saw cannot be answered from outside the terminal (NO_HOOKS).
     * [readyPrompt]: what a text reply to a finished turn would be checked against.
     */
    fun inboxItemFor(
        sessionId: String,
        title: String,
        state: VelinState,
        prompt: PromptFingerprint?,
        promptText: String?,
        promptAtMs: Long?,
        hookNamed: Boolean,
        lastAssistantText: String?,
        readyPrompt: PromptFingerprint?,
        sinceMs: Long?,
    ): InboxItem? = when (state) {
        VelinState.APPROVAL, VelinState.QUESTION -> {
            val kind = if (state == VelinState.QUESTION) InboxKind.QUESTION else InboxKind.PERMISSION
            val dialog = prompt?.takeIf { it.kind == PromptKind.PERMISSION || it.kind == PromptKind.QUESTION }
            val text = promptText.orEmpty()
            InboxItem(
                sessionId, title, kind, text,
                sinceMs = promptAtMs ?: sinceMs,
                prompt = dialog?.takeIf { hookNamed },
                risk = if (kind == InboxKind.PERMISSION) PermRisk.classify(text) else null,
            )
        }
        VelinState.READY -> lastAssistantText?.takeIf { endsWithQuestion(it) }?.let {
            InboxItem(sessionId, title, InboxKind.STOP_QUESTION, questionTail(it), sinceMs, prompt = readyPrompt)
        }
        else -> null
    }

    private val TRAILING_DECOR = charArrayOf('*', '_', '`', ')', '"', '\'', '”', '»', ' ', '\t')

    /** The last non-blank line of [text], stripped of markdown emphasis and quotes, ends with "?". */
    fun endsWithQuestion(text: String): Boolean {
        val last = text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return false
        if (last.startsWith("```")) return false
        return last.trimEnd(*TRAILING_DECOR).endsWith("?")
    }

    /** The paragraph that holds the question, at most [max] characters from its end. */
    fun questionTail(text: String, max: Int = 280): String {
        val para = text.trim().split(Regex("\n\\s*\n")).lastOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (para.length <= max) para else "…" + para.takeLast(max)
    }

    /** One question of an `ask` event's text: `question [option, option] | question [...]`. */
    data class AskQuestion(val question: String, val options: List<String>)

    /**
     * Splits an `ask` event's text (hook-ev.sh: questions joined by " | ", each followed by its
     * option LABELS in brackets; no descriptions, no multiSelect flag, cut at 600 bytes). Display
     * only: a label with ", " or a cut-off tail splits imperfectly, and nothing is sent from it.
     */
    fun parseAsk(text: String): List<AskQuestion> = text.split(" | ").mapNotNull { part ->
        val p = part.trim()
        if (p.isEmpty()) return@mapNotNull null
        val open = p.lastIndexOf(" [")
        if (open > 0 && p.endsWith("]")) {
            AskQuestion(p.substring(0, open).trim(), p.substring(open + 2, p.length - 1).split(", ").map { it.trim() }.filter { it.isNotEmpty() })
        } else AskQuestion(p, emptyList())
    }

    /**
     * Items that left the inbox although no answer went out from here: another device, the
     * terminal or the watch answered them. The screen shows them briefly as "už vyřízeno jinde".
     * [live]: sessions still connected and running Claude — an item that left because its tab
     * dropped or its Claude exited was answered by nobody.
     */
    fun goneElsewhere(previous: List<InboxItem>, now: List<InboxItem>, answeredHere: Set<String>, live: Set<String>): List<InboxItem> {
        val nowKeys = now.mapTo(HashSet()) { it.key }
        return previous.filter { it.key !in nowKeys && it.key !in answeredHere && it.sessionId in live }
    }
}

/**
 * Which permissions the Velín approves only by a hold (50-ui.md §3.2, D2). Works on the text of
 * the `perm` event: "Tool: detail", where hook-ev.sh takes the detail from `description` FIRST and
 * the command only when there is none — so for most Bash calls it sees Claude's description, not
 * the command. The rules therefore cover command shapes AND the words such a description uses.
 * Unknown fails closed: a permission with no text at all is risky.
 */
internal object PermRisk {
    data class Verdict(val risky: Boolean, val reason: String?)

    private fun rx(p: String) = Regex(p, setOf(RegexOption.IGNORE_CASE))

    // Order matters only for the reason shown: the first match names it.
    private val RULES: List<Pair<Regex, String>> = listOf(
        rx("""git\s+push\b[^\n]*(--force|--force-with-lease|\s-f\b|\s\+\S)""") to "git push --force",
        rx("""git\s+push\b""") to "git push",
        rx("""\bforce[- ]push""") to "force push",
        rx("""\bpush\w*""") to "push",
        rx("""git\s+reset\s+--hard""") to "git reset --hard",
        rx("""git\s+clean\s+-\w*f""") to "git clean",
        rx("""git\s+branch\s+-D\b""") to "smazání větve",
        rx("""git\s+(checkout\s+--\s|restore\b|filter-branch|stash\s+(drop|clear)|tag\s+-d\b)""") to "zahození změn v gitu",
        rx("""\brm\s+-\w*[rf]""") to "rm -rf",
        rx("""(^|[\s;&|(])(rm|rmdir|shred|unlink)\s""") to "mazání souborů",
        rx("""\bfind\b[^\n]*\s-delete\b""") to "mazání souborů",
        rx("""\b(drop\s+(table|database|schema)|truncate\s+table|delete\s+from)\b""") to "mazání v databázi",
        rx("""\b(deploy\w*|nasa[dz]\w*)""") to "deploy",
        rx("""\b(npm|cargo|gradle\w*)\s+publish\b|\bgh\s+release\b""") to "publikace",
        rx("""\b(prod|production|produkc\w*)\b""") to "produkce",
        rx("""\b(kubectl\s+(apply|delete)|terraform\s+(apply|destroy)|helm\s+(install|upgrade|uninstall))\b""") to "změna infrastruktury",
        rx("""\b(sudo|systemctl|shutdown|reboot|mkfs\w*|crontab\s+-r)\b""") to "zásah do systému",
        rx("""\bdd\s+if=|>\s*/dev/sd""") to "zápis na disk",
        rx("""\b(kill\s+-9|pkill|killall)\b|tmux\s+kill-(server|session)""") to "ukončování procesů",
        rx("""\b(chmod|chown)\s+-R\b""") to "hromadná práva",
        rx("""\b(curl|wget)\b[^\n|]*\|\s*(ba|z)?sh\b""") to "spuštění staženého skriptu",
        rx("""\bdocker\s+(rm|rmi|system\s+prune|volume\s+rm)\b""") to "mazání v dockeru",
        rx("""\.credentials|\.ssh/|/etc/|\.claude/settings\.json|hook-ev\.sh|\.claude-remote/(drift|restore)\.sh""") to "citlivý soubor",
        rx("""\b(delete|remove|wipe|purge|destroy|overwrite|drop)\b""") to "mazání",
        rx("""\b(sma[zž]\w*|odstra[nň]\w*|vyma[zž]\w*|přepi[sš]\w*|zahoď|zahodit)""") to "mazání",
    )

    fun classify(text: String?): Verdict {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return Verdict(true, "neznámý příkaz")
        val hit = RULES.firstOrNull { (re, _) -> re.containsMatchIn(t) } ?: return Verdict(false, null)
        return Verdict(true, hit.second)
    }
}
