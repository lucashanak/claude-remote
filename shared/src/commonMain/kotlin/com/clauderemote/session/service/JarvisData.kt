package com.clauderemote.session.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * What the Velín reads from and asks of Jarvis' CLI on the server (F3): the task ledger, the watches, the mail
 * proposals, the mode and the briefing. The app never writes Jarvis' files; every change is one CLI call, the same
 * single writer Jarvis uses. Pure: commands are strings, answers are parsed here, the exec lives in the orchestrator.
 */

/** One task of `cr-task list --json` (the fold of `tasks.jsonl` plus `status: [label, detail]`). */
data class JarvisTask(
    val id: String,
    val title: String,
    /** The tmux name the task went to, null when none yet. */
    val to: String?,
    val ac: String?,
    /** `open | done | failed`: the ledger's state. */
    val state: String,
    /** The digest's word for where it stands: zadáno, běží, čeká na tebe, ke kontrole, hotovo, selhalo. */
    val status: String,
    val statusDetail: String?,
    val createdMs: Long?,
    val closedMs: Long?,
    val by: String?,
    val note: String?,
)

/** One watch of `cr-task watches --json`. */
data class JarvisWatch(
    val id: String,
    val kind: String,
    val target: String?,
    val state: String,
    val createdMs: Long?,
    val task: String?,
    /** A readable "what": the target, the ref, the account, whatever the kind has. */
    val what: String,
)

/**
 * Something Jarvis proposed and Lukáš has not confirmed: the card with "Poslat"/"Provést" and "Zahodit". F3 had only
 * mail (`cr-mail status`); F4 reads every kind from `cr-jarvis proposals --json` ([via] = [JarvisProposalSource.JARVIS])
 * and falls back to the mail list on a server whose `cr-jarvis` does not know `proposals` yet.
 */
data class JarvisProposal(
    val id: String,
    /** For mail the tmux name it goes to; for `close`/`restore` the session; for `open` the project (folder or alias). */
    val to: String,
    /** The body the card shows whole before it may be approved: the message, or the facts and account of an action. */
    val text: String,
    val createdMs: Long?,
    val task: String?,
    /**
     * [text] is the full wording (`text`/`detail` of the CLI), not just the subject: only then may "Poslat" approve it
     * — an approval does the whole thing, so Lukáš must have seen all of it (rev-f3-app MED6).
     */
    val fullText: Boolean = false,
    val kind: JarvisProposalKind = JarvisProposalKind.MAIL,
    /** The CLI's one Czech sentence (`title`), null from the mail fallback (the card then words it itself). */
    val title: String? = null,
    /** Server epoch ms after which the proposal lapsed (10 min for actions), null = unknown. */
    val expiresMs: Long? = null,
    val via: JarvisProposalSource = JarvisProposalSource.MAIL,
)

/** The contract's `kind`. An unknown kind is not shown: approving something the app cannot describe is not consent. */
enum class JarvisProposalKind(val wire: String) {
    MAIL("mail"), OPEN("open"), CLOSE("close"), RESTORE("restore");

    companion object {
        fun of(wire: String?): JarvisProposalKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** Which CLI listed the proposal, and so which one decides it. */
enum class JarvisProposalSource { JARVIS, MAIL }

/** `~/.claude-remote/jarvis/mode`: `normal | blocking | dnd[:<ms until>]`. */
data class JarvisMode(val mode: Kind, val untilMs: Long? = null) {
    enum class Kind(val wire: String, val label: String) {
        NORMAL("normal", "Normálně"),
        BLOCKING("blocking", "Jen blokující"),
        DND("dnd", "Nerušit"),
    }
}

/** A CLI call that changes something: [ok], or the CLI's own (Czech) reason. */
data class JarvisRunResult(val ok: Boolean, val message: String)

object JarvisData {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** The status words in the order the Úkoly tab shows them (50-ui §3.3): what waits for Lukáš first. */
    val STATUS_ORDER = listOf("čeká na tebe", "ke kontrole", "běží", "zadáno", "selhalo", "hotovo")

    private fun parse(text: String?): JsonElement? = try {
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { json.parseToJsonElement(it) }
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    /** Null = not a task list (nothing installed, a cut-off answer): the screen says "nejde načíst", never "nic". */
    fun parseTasks(text: String?): List<JarvisTask>? {
        val arr = parse(text) as? JsonArray ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val st = o["status"] as? JsonArray
            val state = o.str("state") ?: "open"
            val label = (st?.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: when (state) { "done" -> "hotovo"; "failed" -> "selhalo"; else -> "zadáno" }
            val detail = (st?.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content
            JarvisTask(
                id = id, title = o.str("title") ?: id, to = o.str("to"), ac = o.str("ac"), state = state,
                status = label, statusDetail = detail, createdMs = o.num("created"), closedMs = o.num("closedAt"),
                by = o.str("by"), note = o.str("note"),
            )
        }
    }

    /** Tasks grouped by [JarvisTask.status] in [STATUS_ORDER]; a word the app does not know goes last. Newest first. */
    fun groupTasks(tasks: List<JarvisTask>): List<Pair<String, List<JarvisTask>>> = tasks
        .groupBy { it.status }
        .toList()
        .sortedBy { (k, _) -> STATUS_ORDER.indexOf(k).let { if (it < 0) STATUS_ORDER.size else it } }
        .map { (k, v) -> k to v.sortedByDescending { it.createdMs ?: 0L } }

    fun parseWatches(text: String?): List<JarvisWatch>? {
        val arr = parse(text) as? JsonArray ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val kind = o.str("kind") ?: "?"
            val target = o.str("target")
            val what = when (kind) {
                "stop" -> "${target ?: "?"} doběhne"
                "waiting" -> "${target ?: "?"} bude čekat na tebe"
                "ci" -> "CI ${o.str("ref") ?: ""}".trim()
                "usage" -> "limit ${o.str("acctName") ?: "?"} pod ${o.num("below") ?: "?"} %"
                else -> target ?: kind
            }
            JarvisWatch(id, kind, target, o.str("state") ?: "open", o.num("created"), o.str("task"), what)
        }
    }

    /**
     * The `proposed` messages of `cr-mail status --json` (`{"off": …, "messages": [...]}`); the full `text` when the
     * CLI gives it, else the subject. Null = not that shape.
     */
    fun parseProposals(text: String?): List<JarvisProposal>? {
        val root = parse(text) as? JsonObject ?: return null
        val msgs = root["messages"] as? JsonArray ?: return null
        return msgs.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            if (o.str("state") != "proposed") return@mapNotNull null
            val id = o.str("id")?.takeIf { MAIL_ID.matches(it) } ?: return@mapNotNull null
            val full = o.str("text")
            JarvisProposal(
                id = id, to = o.str("to") ?: "?", text = full ?: o.str("subject") ?: "",
                createdMs = o.num("created"), task = o.str("task"), fullText = full != null,
            )
        }.sortedBy { it.createdMs ?: 0L }
    }

    /**
     * `cr-jarvis proposals --json` (F4 contract): `[{id, kind, target, title, detail, created, expires}]`, oldest
     * first. An entry without an id of the two known shapes, without a target or of an unknown kind is dropped (a
     * future kind the app cannot describe is decided in Jarvis, not here). Null = not that shape: an older
     * `cr-jarvis` without `proposals` answers with usage text, and the caller falls back to the mail list.
     */
    fun parseJarvisProposals(text: String?): List<JarvisProposal>? {
        val arr = parse(text) as? JsonArray ?: return null
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val kind = JarvisProposalKind.of(o.str("kind")) ?: return@mapNotNull null
            val id = o.str("id")?.takeIf { (if (kind == JarvisProposalKind.MAIL) MAIL_ID else ACTION_ID).matches(it) }
                ?: return@mapNotNull null
            val target = o.str("target") ?: return@mapNotNull null
            val detail = o.str("detail")
            JarvisProposal(
                id = id, to = target, text = detail ?: o.str("title").orEmpty(), createdMs = o.num("created"),
                task = o.str("task"), fullText = detail != null, kind = kind, title = o.str("title"),
                expiresMs = o.num("expires"), via = JarvisProposalSource.JARVIS,
            )
        }.sortedBy { it.createdMs ?: 0L }
    }

    /**
     * The proposals of [combined] ([PROPOSALS]): `cr-jarvis proposals` when the server knows it, else the mail of
     * `cr-mail status`. Null = neither answered in shape.
     */
    fun parseCombinedProposals(combined: String?): List<JarvisProposal>? {
        val (jarvis, mail) = splitProposals(combined)
        return parseJarvisProposals(jarvis) ?: parseProposals(mail)
    }

    /** The `cr-mail status --json` half of [PROPOSALS] (for [mailOff]). */
    fun mailHalf(combined: String?): String? = splitProposals(combined).second

    private fun splitProposals(combined: String?): Pair<String?, String?> {
        val t = combined ?: return null to null
        val i = t.indexOf(MAIL_MARK)
        return if (i < 0) t to null else t.substring(0, i) to t.substring(i + MAIL_MARK.length)
    }

    /**
     * "Poslat"/"Provést" is enabled only for a proposal whose full text arrived AND was shown whole ([seenWhole]: it
     * fit, or the user opened "Zobrazit celé"), and that has not lapsed by this device's clock ([nowMs]; the server
     * refuses a lapsed one anyway, the button only does not offer what cannot work).
     */
    fun canApprove(p: JarvisProposal, seenWhole: Boolean, nowMs: Long? = null): Boolean =
        p.fullText && seenWhole && !expired(p, nowMs)

    fun expired(p: JarvisProposal, nowMs: Long?): Boolean = nowMs != null && p.expiresMs != null && nowMs >= p.expiresMs

    /** The card's heading: what approving does, in a few words. */
    fun heading(p: JarvisProposal): String = when (p.kind) {
        JarvisProposalKind.MAIL -> "Návrh zprávy → ${aliasOf(p.to)}"
        JarvisProposalKind.OPEN -> "Návrh: otevřít ${p.to.trimEnd('/').substringAfterLast('/').ifBlank { p.to }}"
        JarvisProposalKind.CLOSE -> "Návrh: zavřít ${aliasOf(p.to)}"
        JarvisProposalKind.RESTORE -> "Návrh: obnovit ${aliasOf(p.to)}"
    }

    /** The approve button's word: mail is sent, an action is carried out. */
    fun approveLabel(p: JarvisProposal): String = if (p.kind == JarvisProposalKind.MAIL) "Poslat" else "Provést"

    /** What the screen says after an approval went through: the CLI's own sentence when it has one. */
    fun approvedNotice(p: JarvisProposal, cliMessage: String): String =
        cliMessage.takeIf { it.isNotBlank() && it != "Hotovo" }
            ?: if (p.kind == JarvisProposalKind.MAIL) "Odesláno do fronty: ${aliasOf(p.to)}" else "Provedeno: ${heading(p).removePrefix("Návrh: ")}"

    /** `claude-server-kontexta--soniox` → `soniox`. */
    private fun aliasOf(tmux: String): String = tmux.substringAfterLast("--").ifBlank { tmux }

    /** Whether Jarvis' mail is switched off (`cr-mail off`): nothing pastes, an approval would only queue. */
    fun mailOff(text: String?): Boolean =
        ((parse(text) as? JsonObject)?.get("off") as? JsonPrimitive)?.booleanOrNull == true

    /** Unknown or garbled = normal: the server falls back the same way, and DND must never be assumed. */
    fun parseMode(text: String?): JarvisMode {
        val t = text?.trim().orEmpty()
        val head = t.substringBefore(':')
        val kind = JarvisMode.Kind.entries.firstOrNull { it.wire == head } ?: return JarvisMode(JarvisMode.Kind.NORMAL)
        val until = if (kind == JarvisMode.Kind.DND) t.substringAfter(':', "").toLongOrNull() else null
        return JarvisMode(kind, until)
    }

    /** The mode as it is effective at [nowMs]: a DND whose time ran out is normal again. */
    fun effectiveMode(m: JarvisMode, nowMs: Long): JarvisMode =
        if (m.mode == JarvisMode.Kind.DND && m.untilMs != null && m.untilMs <= nowMs) JarvisMode(JarvisMode.Kind.NORMAL) else m

    // ── Commands ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Absolute: an exec shell is not a login shell, and `~/.local/bin` may not be on its PATH. */
    private const val BIN = "~/.local/bin/"
    private val TASK_ID = Regex("t-[A-Za-z0-9-]{1,40}")
    private val MAIL_ID = Regex("m-[A-Za-z0-9-]{1,40}")
    /** `x-<UTC>-<hex4>` (F4 contract); `.`, `:` and `_` allowed for the UTC part — all inert in a shell word. */
    private val ACTION_ID = Regex("x-[A-Za-z0-9._:-]{1,60}")
    private val INSTALL_ID = Regex("[A-Za-z0-9-]{1,40}")

    /** Exit status as the last line, stderr folded in: a refusal's reason is what the user is shown. */
    private const val RC = "__cr_rc="

    private fun checked(cmd: String) = "$cmd 2>&1; echo $RC\$?"

    const val TASK_LIST = "${BIN}cr-task list --all --json"
    const val WATCHES = "${BIN}cr-task watches --json"
    const val MAIL_STATUS = "${BIN}cr-mail status --json"
    private const val MAIL_MARK = "\n__cr_mail__\n"

    /**
     * Every pending proposal and the mail switch in ONE exec (one channel, not two): `cr-jarvis proposals --json`,
     * a marker, `cr-mail status --json`. An older `cr-jarvis` without `proposals` prints usage instead of JSON and the
     * mail half still carries the F3 list ([parseCombinedProposals]).
     */
    const val PROPOSALS = "${BIN}cr-jarvis proposals --json 2>/dev/null; printf '\\n__cr_mail__\\n'; ${BIN}cr-mail status --json"
    const val MODE_READ = "cat ~/.claude-remote/jarvis/mode 2>/dev/null; echo"

    fun taskShow(id: String): String {
        require(TASK_ID.matches(id)) { "not a task id" }
        return "${BIN}cr-task show $id --json"
    }

    /** `cr-mail approve|reject <m-id> --by app:<installId>`: the out-of-model confirmation of a proposal (F2 review). */
    fun decide(id: String, approve: Boolean, installId: String): String {
        require(MAIL_ID.matches(id)) { "not a mail id" }
        require(INSTALL_ID.matches(installId)) { "not an install id" }
        return checked("${BIN}cr-mail ${if (approve) "approve" else "reject"} $id --by app:$installId")
    }

    /**
     * The decision of [p] through the CLI that listed it: `cr-jarvis approve|reject <id> --by app:<installId>` (F4: it
     * hands mail on to `cr-mail` itself), or [decide] for a proposal of the F3 mail fallback.
     */
    fun decideProposal(p: JarvisProposal, approve: Boolean, installId: String): String {
        if (p.via == JarvisProposalSource.MAIL) return decide(p.id, approve, installId)
        require(MAIL_ID.matches(p.id) || ACTION_ID.matches(p.id)) { "not a proposal id" }
        require(INSTALL_ID.matches(installId)) { "not an install id" }
        return checked("${BIN}cr-jarvis ${if (approve) "approve" else "reject"} ${p.id} --by app:$installId")
    }

    /**
     * How long an approval may take: opening and restoring wait for claude to start on the server (cr-session gives
     * it up to ~20 s), mail and closes answer at once.
     */
    fun decideTimeoutMs(p: JarvisProposal, approve: Boolean): Long =
        if (approve && (p.kind == JarvisProposalKind.OPEN || p.kind == JarvisProposalKind.RESTORE)) 35_000L else 10_000L

    /**
     * `cr-jarvis mode normal|blocking|dnd [--for <duration>]`; [forMinutes] only with DND. Always a DURATION, never a
     * clock time: the server reads `--until HH:MM` in its own time zone (UTC), the phone's 7:00 is not its 7:00
     * (rev-f3-app HIGH). Null = until cancelled (allowed to Lukáš; Jarvis himself gets at most 12 h on the server).
     */
    fun setMode(kind: JarvisMode.Kind, forMinutes: Int? = null): String {
        val m = forMinutes?.takeIf { kind == JarvisMode.Kind.DND }
        require(m == null || m in 1..24 * 60) { "duration out of range" }
        return checked("${BIN}cr-jarvis mode ${kind.wire}" + (m?.let { " --for ${forArg(it)}" } ?: ""))
    }

    /** 90 → `1h30m`, 60 → `1h`, 45 → `45m` (the CLI's `parse_in`). */
    fun forArg(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return (if (h > 0) "${h}h" else "") + (if (m > 0 || h == 0) "${m}m" else "")
    }

    /** Minutes from [nowHour]:[nowMinute] (local) to the next [hour]:00 local, 1..1440. */
    fun minutesUntil(nowHour: Int, nowMinute: Int, hour: Int): Int {
        val d = ((hour * 60 - (nowHour * 60 + nowMinute)) % 1440 + 1440) % 1440
        return if (d == 0) 1440 else d
    }

    /** Morning before noon, evening after: the briefing that fits the hour the button is pressed. */
    fun briefing(hour: Int): String = "${BIN}cr-digest briefing ${if (hour < 12) "morning" else "evening"} --say"

    /**
     * The answer of a [checked] command. No status line = the answer was cut off: not ok, and said so — the change may
     * or may not have happened, the next read shows which.
     */
    fun parseRun(out: String?): JarvisRunResult {
        val lines = out.orEmpty().trimEnd().lines()
        val rc = lines.lastOrNull()?.takeIf { it.startsWith(RC) }?.removePrefix(RC)?.trim()?.toIntOrNull()
            ?: return JarvisRunResult(false, "Server neodpověděl celý – stav ověř obnovením")
        val msg = lines.dropLast(1).joinToString("\n").trim().removePrefix("cr-mail: ").removePrefix("cr-jarvis: ")
        return JarvisRunResult(rc == 0, msg.ifBlank { if (rc == 0) "Hotovo" else "Odmítnuto (kód $rc)" })
    }

    /** Mail of `cr-task show --json` (`"mail": ["m-… state", …]`). */
    fun parseTaskMail(text: String?): List<String> =
        ((parse(text) as? JsonObject)?.get("mail") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
}
