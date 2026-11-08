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

/** A mail Jarvis proposed and Lukáš has not confirmed: the card with "Poslat" / "Zahodit". */
data class JarvisProposal(
    val id: String,
    val to: String,
    val text: String,
    val createdMs: Long?,
    val task: String?,
)

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
            JarvisProposal(
                id = id, to = o.str("to") ?: "?", text = o.str("text") ?: o.str("subject") ?: "",
                createdMs = o.num("created"), task = o.str("task"),
            )
        }.sortedBy { it.createdMs ?: 0L }
    }

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
    private val INSTALL_ID = Regex("[A-Za-z0-9-]{1,40}")
    private val HHMM = Regex("([01]?[0-9]|2[0-3]):[0-5][0-9]")

    /** Exit status as the last line, stderr folded in: a refusal's reason is what the user is shown. */
    private const val RC = "__cr_rc="

    private fun checked(cmd: String) = "$cmd 2>&1; echo $RC\$?"

    const val TASK_LIST = "${BIN}cr-task list --all --json"
    const val WATCHES = "${BIN}cr-task watches --json"
    const val MAIL_STATUS = "${BIN}cr-mail status --json"
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

    /** `cr-jarvis mode normal|blocking|dnd [--until HH:MM]`; [until] only with DND. */
    fun setMode(kind: JarvisMode.Kind, until: String? = null): String {
        val u = until?.takeIf { kind == JarvisMode.Kind.DND }
        require(u == null || HHMM.matches(u)) { "not HH:MM" }
        return checked("${BIN}cr-jarvis mode ${kind.wire}" + (u?.let { " --until $it" } ?: ""))
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
