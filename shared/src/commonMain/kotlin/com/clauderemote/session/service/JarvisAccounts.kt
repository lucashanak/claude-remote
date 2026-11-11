package com.clauderemote.session.service

import com.clauderemote.model.ClaudeAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Jarvis F5d (2026-11-11): the accounts Jarvis may run on. Lukáš picks at least two in Settings → Jarvis; jarvis-d
 * keeps Jarvis on the one with the most headroom and moves him when his own runs low.
 *
 * The contract (`f5d/00-brief.md`): `~/.claude-remote/jarvis/accounts`, one slot per line — an account slug of
 * `~/.claude-remote/accounts/<slug>`, or `default` for the `~/.claude` login. Only the app writes it, atomically
 * (tmp + mv), as a short exec argument. Fewer than two valid lines = autopick off, today's behaviour: so a choice of
 * one is written as an EMPTY file, never as one line.
 */
object JarvisAccountsFile {
    const val PATH = "~/.claude-remote/jarvis/accounts"
    const val MIN_SLOTS = 2
    /** More would be no choice anyone makes; the cap keeps the write well under the 4 KB exec argument. */
    const val MAX_SLOTS = 16
    private const val SLOT_MAX_LEN = 64
    /** The account-dir charset (AccountService's slug guard): also keeps every line shell- and parse-safe. */
    private val SLOT = Regex("^[A-Za-z0-9._@-]+$")

    const val TOO_FEW = "Automatický výběr účtu potřebuje aspoň 2 účty"

    /** A slot the file may hold: `default` or a usable slug (no `.`/`..`, no `/`, no leading dot). */
    fun validSlot(s: String): Boolean =
        s.length in 1..SLOT_MAX_LEN && SLOT.matches(s) && s != "." && s != ".." && !s.startsWith(".") && !s.contains("..")

    /** Valid, distinct slots of [chosen] in their order, at most [MAX_SLOTS]. */
    fun normalize(chosen: List<String>): List<String> =
        chosen.map { it.trim() }.filter { validSlot(it) }.distinct().take(MAX_SLOTS)

    /** Autopick is on with this choice (at least [MIN_SLOTS] valid slots). */
    fun enabled(chosen: List<String>): Boolean = normalize(chosen).size >= MIN_SLOTS

    /** The file's content: one slot per line with a final newline, or "" when autopick is off. */
    fun serialize(chosen: List<String>): String {
        val slots = normalize(chosen)
        return if (slots.size < MIN_SLOTS) "" else slots.joinToString("\n", postfix = "\n")
    }

    /** What the file says (blank lines, `#` comments and invalid lines skipped); empty = off or missing. */
    fun parse(text: String?): List<String> =
        normalize(text.orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList())

    /** The slot an account is written as: `default` for the `~/.claude` login, else its slug. */
    fun slotOf(a: ClaudeAccount): String = if (a.isDefault) ClaudeAccount.DEFAULT_SLUG else a.slug

    /** Reads the file; the marker tells "missing or unreadable" from "empty" (both are "off" for jarvis-d). */
    const val READ = "cat $PATH 2>/dev/null; echo __cr_end__"

    /** [READ]'s output, or null when the read did not finish (no answer: the UI says so, never "nothing chosen"). */
    fun parseRead(out: String?): List<String>? {
        val o = out ?: return null
        val end = o.lastIndexOf("__cr_end__")
        if (end < 0) return null
        return parse(o.substring(0, end))
    }

    /**
     * The atomic write: base64 of [serialize] as a short argument (≤ [MAX_SLOTS] × 64 chars → under 2 KB of b64),
     * decoded into a tmp file in the same directory and renamed over the file. `OK` only after the rename.
     */
    fun writeCommand(chosen: List<String>): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(serialize(chosen).encodeToByteArray())
        return "mkdir -p ~/.claude-remote/jarvis && printf %s '$b64' | base64 -d > ~/.claude-remote/jarvis/.accounts.tmp && " +
            "mv -f ~/.claude-remote/jarvis/.accounts.tmp $PATH && echo __cr_ok__"
    }

    fun writeOk(out: String?): Boolean = out?.contains("__cr_ok__") == true
}

/** One account jarvis-d considered (`status.json` → `jarvisAccount.candidates[]`). */
data class JarvisAccountCandidate(
    val slot: String,
    val email: String?,
    /** Percent 0–100 of the 5-hour window, null = unknown. */
    val fiveHour: Double?,
    /** Percent 0–100 of the 7-day window, null = unknown. */
    val sevenDay: Double?,
    /** Age of that usage reading in seconds, null = unknown. */
    val ageS: Long?,
)

/** The last move ([JarvisAccountInfo.lastSwitch]). [atMs] = epoch ms when the server gave a time; [atText] = as given otherwise. */
data class JarvisAccountSwitch(
    val atMs: Long?,
    val atText: String?,
    val from: String?,
    val to: String?,
    val why: String?,
    /** The switch request it carried out (the switch contract's `s-…`), null = not given. */
    val id: String? = null,
)

/** A switch jarvis-d asked the app for and nobody has carried out yet (`jarvisAccount.request`, switch contract). */
data class JarvisAccountRequest(val id: String?, val to: String?, val why: String?, val expiresMs: Long?)

/** `status.json` → `jarvisAccount` (F5d contract), as the Velín shows it. */
data class JarvisAccountInfo(
    val slot: String?,
    val email: String?,
    val fiveHour: Double?,
    val sevenDay: Double?,
    val candidates: List<JarvisAccountCandidate>,
    /** `on`, `off`, or the reason it is not working. */
    val autopick: String?,
    val lastSwitch: JarvisAccountSwitch?,
    val request: JarvisAccountRequest? = null,
) {
    val autopickOn: Boolean get() = autopick == "on"

    /** The account's label: its e-mail when known, else the slot ("default" says "výchozí účet"). */
    val label: String get() = JarvisAccountView.label(slot, email)
}

object JarvisAccountView {
    /**
     * Reads only `jarvisAccount` of Jarvis' `status.json` on the server (the whole file lists every session and is
     * rewritten every ~5 s): `null` when there is none, `__cr_none__` when the file cannot be read.
     */
    const val READ = "python3 -c 'import json,os,sys\n" +
        "try: d=json.load(open(os.path.expanduser(\"~/.claude-remote/jarvis/status.json\")))\n" +
        "except Exception: print(\"__cr_none__\"); sys.exit(0)\n" +
        "print(json.dumps(d.get(\"jarvisAccount\") if isinstance(d,dict) else None))' 2>/dev/null"

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Why there is nothing to show: [READ] failed, or jarvis-d does not write the field yet. */
    sealed interface Read {
        data class Ok(val info: JarvisAccountInfo) : Read
        /** Jarvis' status has no `jarvisAccount` (an older jarvis-d). */
        data object Absent : Read
        data object Unreadable : Read
    }

    fun parse(out: String?): Read {
        val text = out?.trim() ?: return Read.Unreadable
        if (text.isEmpty() || text.contains("__cr_none__")) return Read.Unreadable
        val el = try { JSON.parseToJsonElement(text.lineSequence().last { it.isNotBlank() }) } catch (_: Exception) { return Read.Unreadable }
        if (el is JsonNull) return Read.Absent
        val o = el as? JsonObject ?: return Read.Unreadable
        return Read.Ok(
            JarvisAccountInfo(
                slot = o.str("slot"),
                email = o.str("email"),
                fiveHour = o.pct("fiveHour"),
                sevenDay = o.pct("sevenDay"),
                candidates = (o["candidates"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val co = c as? JsonObject ?: return@mapNotNull null
                    val slot = co.str("slot") ?: return@mapNotNull null
                    JarvisAccountCandidate(slot, co.str("email"), co.pct("fiveHour"), co.pct("sevenDay"), co.num("ageS")?.toLong())
                },
                autopick = o["autopick"]?.let { a ->
                    // a bare boolean is read as on/off; anything else is the reason as written
                    (a as? JsonPrimitive)?.let { p -> p.booleanOrNull?.let { if (it) "on" else "off" } ?: p.content.takeIf { p.isString } }
                },
                lastSwitch = (o["lastSwitch"] as? JsonObject)?.let { s ->
                    val atNum = s.num("at")
                    JarvisAccountSwitch(
                        // epoch seconds or ms, whichever it is
                        atMs = atNum?.let { ms(it) },
                        atText = if (atNum == null) s.str("at") else null,
                        from = s.str("from"), to = s.str("to"), why = s.str("why"), id = s.str("id"),
                    )
                },
                request = (o["request"] as? JsonObject)?.let { r ->
                    JarvisAccountRequest(r.str("id"), r.str("to"), r.str("why"), r.num("expires")?.let { ms(it) })
                },
            ),
        )
    }

    /** Epoch seconds or ms, whichever it is. */
    private fun ms(v: Double): Long = if (v < 1e12) (v * 1000).toLong() else v.toLong()

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    /** A percent; a fraction 0–1 written by mistake is not guessed into a percent (shown as given). */
    private fun JsonObject.pct(k: String): Double? = num(k)?.takeIf { it >= 0 && it.isFinite() }

    fun label(slot: String?, email: String?): String = when {
        !email.isNullOrBlank() -> email
        slot == null -> "?"
        slot == ClaudeAccount.DEFAULT_SLUG -> "výchozí účet"
        else -> slot
    }

    /** "5h 42 % · 7d 91 %", "?" for an unknown window. */
    fun usage(fiveHour: Double?, sevenDay: Double?): String = "5h ${pct(fiveHour)} · 7d ${pct(sevenDay)}"

    private fun pct(v: Double?): String = v?.let { "${kotlin.math.round(it).toInt()} %" } ?: "?"

    /** The autopick line: on, off (fewer than two chosen), or the reason it does not work. */
    fun autopickText(a: String?): String = when (a) {
        null -> "automatický výběr: neznámý stav"
        "on" -> "automatický výběr: zapnutý"
        "off" -> "automatický výběr: vypnutý (méně než 2 účty)"
        else -> "automatický výběr: $a"
    }

    /** "hanakl@… → lukashanak@… (došel týden)". */
    fun switchText(s: JarvisAccountSwitch, now: Long, accounts: Map<String, String> = emptyMap()): String {
        fun l(slot: String?) = slot?.let { accounts[it] ?: label(it, null) } ?: "?"
        val whenText = s.atMs?.let { "před " + com.clauderemote.ui.Velin.formatAge(it, now) } ?: s.atText
        return "${l(s.from)} → ${l(s.to)}" + (s.why?.let { " ($it)" } ?: "") + (whenText?.let { " · $it" } ?: "")
    }
}
