package com.clauderemote.session.service

import com.clauderemote.model.SessionActivity

/** What a reply from outside the app (phone notification, watch) answers. */
enum class PromptKind {
    /** Claude finished a turn (a Stop marker names it) and sits at its input box. */
    READY,
    /** A tool permission dialog, or a selector the screen cannot tell apart from one. Only Ano/Ne answers it. */
    PERMISSION,
    /** An AskUserQuestion (hook `ask`): Ano/Ne or a typed answer. */
    QUESTION,
    /**
     * No prompt at all: an instruction for a session that is working (Claude Code queues it) or
     * idle without a known completion. Allowed only when the server's event log vouches for it.
     */
    INSTRUCTION,
}

/**
 * Identity of the prompt an actionable notification (phone alert, watch notification, watch
 * detail screen) was raised for. [key] is the notify event key ([NotifyKeys]): one hook `perm` /
 * `ask`, one screen detection, one Stop marker. A reply carries it back so the phone can tell
 * whether the session still shows THAT prompt before typing anything into it.
 */
data class PromptFingerprint(val key: String, val kind: PromptKind) {
    /** One string, so it fits an Intent extra and the watch wire models unchanged in shape. */
    fun encode(): String = "${kind.name}:$key"

    companion object {
        /** null for anything that is not an [encode] result: a reply without an identity fails closed. */
        fun decode(s: String?): PromptFingerprint? {
            if (s == null) return null
            val i = s.indexOf(':')
            if (i <= 0 || i == s.length - 1) return null
            val kind = PromptKind.entries.firstOrNull { it.name == s.substring(0, i) } ?: return null
            return PromptFingerprint(s.substring(i + 1), kind)
        }
    }
}

/** Why a reply was not sent, with the short Czech text the phone or the watch shows for it. */
enum class RefuseReason(val message: String) {
    /** The watch app predates prompt identities and sends none. */
    WATCH_OUTDATED("Aktualizuj aplikaci na hodinkách"),
    /** The notification names no prompt (an event the app could not identify). */
    NO_PROMPT("Na tohle odpověď poslat nejde – otevři aplikaci"),
    NOT_CONNECTED("Session není připojená"),
    PROMPT_CHANGED("Dotaz se mezitím změnil – otevři aplikaci"),
    /** Free text for a permission dialog: Enter would pick its default "Yes". */
    YES_NO_ONLY("Na schválení odpověz Ano/Ne"),
    /** The server could not confirm the prompt: the exec never ran, or nothing names a Stop marker. */
    NOT_VERIFIABLE("Stav session nejde ověřit – otevři aplikaci"),
    /** No hook event can vouch for it: a server without hooks (or switched off), a dialog only the screen saw. */
    NO_HOOKS("Bez hooků nejde ověřit – odpověz v aplikaci"),
    /** This prompt already got an answer from here (a repeated Ano, phone and watch at once). */
    ALREADY_ANSWERED("Už odpovězeno"),
    /** The exec broke off after the keys may have gone out: saying "not sent" would invite a second one. */
    MAYBE_SENT("Možná odesláno – zkontroluj v aplikaci"),
    /** The text went in, but the prompt moved before Enter: it waits in the input, unsent. */
    TYPED_NOT_SENT("Text je napsaný, ale neodeslaný – dokonči v aplikaci"),
    /** Jarvis' conversation mode (F5a): his input box holds a draft (or could not be read), so nothing is typed. */
    DRAFT_IN_INPUT("Ve vstupu je rozepsaný text – dokonči ho v aplikaci"),
}

/**
 * What the server's own event log must still say right before the keys go in
 * ([ReplyGuard.serverCommand]): the app's view of the session can lag behind it.
 */
sealed interface ServerExpectation {
    /** The newest `claude-remote-ev` line of the pane is this very `perm` / `ask`. */
    data class Dialog(val ev: String, val atMs: Long) : ServerExpectation
    /** The newest line is a `stop`; with no v2 line at all, the newest legacy Stop marker is at least [stopEpochSec]. */
    data class Ready(val stopEpochSec: Long) : ServerExpectation
    /** The newest line is a `stop` (v2 only). */
    data object Idle : ServerExpectation
    /** The newest line exists and is not a `perm` / `ask` (v2 only). */
    data object Working : ServerExpectation
}

/**
 * Decides whether a reply given from outside the app (the notification's inline reply, the
 * watch) may be typed into the session. Every session runs in yolo mode, so a permission
 * dialog is the last safety gate: an "Ano" written for command A must never approve command B
 * that took A's place while the notification sat on the wrist. Anything unknown fails closed.
 *
 * Two stages: [check] against the app's state, then [serverCommand], which checks the server's
 * event log and sends the keys in the same shell, so nothing the phone has not read yet can
 * slip in between.
 */
object ReplyGuard {
    enum class Reply {
        /** Free text, followed by Enter. */
        TEXT,
        /** The watch's Ano/Ne: a dialog answer. */
        APPROVE,
    }

    sealed interface Verdict {
        data class Send(val expect: ServerExpectation) : Verdict
        data class Refuse(val reason: RefuseReason, val detail: String) : Verdict
    }

    /** Key of a [PromptKind.INSTRUCTION] prompt, which no event names. */
    const val INSTRUCTION_KEY = "instruction"

    private fun refuse(reason: RefuseReason, detail: String) = Verdict.Refuse(reason, detail)

    private fun PromptKind.isDialog() = this == PromptKind.PERMISSION || this == PromptKind.QUESTION

    /**
     * [notified]: the prompt the user answered (from the notification or the watch row).
     * [current]: the newest prompt the session announced, null once it went back to work.
     * [activity]: the session's current activity. [deliverable]: the session's transport is up;
     * with it down the app's view of the session is stale.
     */
    internal fun check(
        notified: PromptFingerprint?,
        current: PromptTokens.Current?,
        activity: SessionActivity?,
        deliverable: Boolean,
        reply: Reply,
    ): Verdict {
        if (notified == null) return refuse(RefuseReason.NO_PROMPT, "the reply names no prompt")
        if (!deliverable) return refuse(RefuseReason.NOT_CONNECTED, "the session is not connected")
        return when (notified.kind) {
            PromptKind.PERMISSION, PromptKind.QUESTION -> when {
                // Text + Enter in a permission selector picks its default, "Yes".
                notified.kind == PromptKind.PERMISSION && reply == Reply.TEXT ->
                    refuse(RefuseReason.YES_NO_ONLY, "free text for a permission dialog")
                activity != SessionActivity.APPROVAL_NEEDED ->
                    refuse(RefuseReason.PROMPT_CHANGED, "no dialog is open now ($activity)")
                current == null || !current.names(notified.key) ->
                    refuse(RefuseReason.PROMPT_CHANGED, "another prompt is open now (${current?.prompt?.key})")
                else -> current.hookEvent()?.let { (ev, at) -> Verdict.Send(ServerExpectation.Dialog(ev, at)) }
                    // A dialog only the screen saw: the server log cannot confirm it.
                    ?: refuse(RefuseReason.NO_HOOKS, "no hook event names this dialog")
            }
            PromptKind.READY -> when {
                reply != Reply.TEXT -> refuse(RefuseReason.PROMPT_CHANGED, "a dialog answer for a finished turn")
                activity != SessionActivity.WAITING_FOR_INPUT ->
                    refuse(RefuseReason.PROMPT_CHANGED, "the session is not at its input prompt ($activity)")
                current != null && current.prompt.kind.isDialog() ->
                    refuse(RefuseReason.PROMPT_CHANGED, "a dialog was announced last (${current.prompt.key})")
                // A later completion is fine too (the server checks that the session is idle), so
                // the key only has to name a real Stop marker.
                else -> NotifyKeys.stopEpochSec(notified.key)?.let { Verdict.Send(ServerExpectation.Ready(it)) }
                    ?: refuse(RefuseReason.NOT_VERIFIABLE, "no Stop marker names this prompt")
            }
            PromptKind.INSTRUCTION -> when {
                reply != Reply.TEXT -> refuse(RefuseReason.PROMPT_CHANGED, "a dialog answer without a dialog")
                current != null && current.prompt.kind.isDialog() ->
                    refuse(RefuseReason.PROMPT_CHANGED, "a dialog was announced last (${current.prompt.key})")
                // Claude Code queues text typed while it works.
                activity == SessionActivity.WORKING -> Verdict.Send(ServerExpectation.Working)
                activity == SessionActivity.WAITING_FOR_INPUT -> Verdict.Send(ServerExpectation.Idle)
                else -> refuse(RefuseReason.PROMPT_CHANGED, "the session takes no input now ($activity)")
            }
        }
    }

    /**
     * Jarvis' conversation mode (F5a, decision 14d/14e): a spoken instruction goes in only while Jarvis is idle at his
     * prompt — never queued behind a running turn (the answer read aloud would belong to another question), never
     * into a dialog (a spoken "ano" must never approve a permission or answer a question). Stricter than an
     * [PromptKind.INSTRUCTION] from the watch, which a working session takes too; the server then checks the same
     * [ServerExpectation.Idle] and, with `emptyInput`, that his input box holds no draft.
     */
    internal fun voiceCheck(current: PromptTokens.Current?, activity: SessionActivity?, deliverable: Boolean): Verdict = when {
        !deliverable -> refuse(RefuseReason.NOT_CONNECTED, "the session is not connected")
        current != null && current.prompt.kind.isDialog() ->
            refuse(RefuseReason.PROMPT_CHANGED, "a dialog was announced last (${current.prompt.key})")
        activity != SessionActivity.WAITING_FOR_INPUT -> refuse(RefuseReason.PROMPT_CHANGED, "not idle at the prompt ($activity)")
        else -> Verdict.Send(ServerExpectation.Idle)
    }

    /**
     * The prompt a view of the session offers a reply to (the watch row, captured again when the
     * user starts answering): [notified] when it agrees with [activity], an instruction for a
     * working or an unannounced idle session, nothing for an approval nobody announced.
     */
    fun promptShown(notified: PromptFingerprint?, activity: SessionActivity?): PromptFingerprint? = when (activity) {
        SessionActivity.WAITING_FOR_INPUT -> notified?.takeIf { it.kind == PromptKind.READY }
            ?: PromptFingerprint(INSTRUCTION_KEY, PromptKind.INSTRUCTION)
        SessionActivity.APPROVAL_NEEDED -> notified?.takeIf { it.kind.isDialog() }
        SessionActivity.WORKING -> PromptFingerprint(INSTRUCTION_KEY, PromptKind.INSTRUCTION)
        else -> null
    }

    private fun sq(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Output token of [serverCommand]. */
    const val SERVER_TOKEN = "CR_REPLY"

    /**
     * tmux reads an argument ending in `;` as a command separator (the `;` is dropped and the
     * rest of the argv runs as a new command); `\;` keeps it literal. Only the LAST character
     * matters, and the rule also covers a text that already ends in `\;`.
     */
    internal fun tmuxLiteral(text: String): String = if (text.endsWith(";")) text.dropLast(1) + "\\;" else text

    /**
     * One shell command that checks the pane's newest hook event against [expect] and, only if
     * it matches, types [text] into the MAIN pane of tmux session [tmux] (the first pane of
     * `list-panes -s`: the one whose Claude writes the events; a split or a teammate pane is
     * never the target) and presses Enter. Check and keys run in the same shell on the server,
     * so the gap between them is milliseconds instead of a round trip.
     *
     * [approve] (`y` / `n`): text and Enter in ONE tmux call, no gap at all. Free text is typed
     * first and Enter follows 0.1 s later (a paste needs to settle); the newest line is read
     * again right before Enter, and if it moved (a dialog opened meanwhile) Enter is not sent —
     * the typed text stays in the input, but no selector default gets picked.
     *
     * [emptyInput] (Jarvis' conversation mode): right before the first key the pane is captured and its input box must
     * be empty — the row under a rule that starts with `❯`+NBSP, once the dim placeholder and the reversed cursor are
     * cut (the same reading as `cr-session facts`' draft, [CloseFacts]). A draft, or no box at all (another screen),
     * prints `CR_REPLY DRAFT` and nothing is typed: the spoken text would be glued to whatever was typed there.
     *
     * `claude-remote-ev <EV> <name_b64> <epoch_ms> <pid> <extra_b64>` lines come from
     * [HookCommands.EVENTS_PATH]; legacy Stop markers (`claude-remote-notify <name> <epoch_s>`)
     * from both marker files the notify watcher tails (the newest epoch wins). A switched-off or
     * full events file counts as "no hooks". Prints `CR_REPLY SENDING` before the first key and
     * then `CR_REPLY SENT`, or one of `STALE|STALE_TYPED|NOHOOKS|NOPANE|DRAFT|FAILED`.
     */
    // Spelled out at every call, not kept in a shell variable: an unquoted `$TM` is not
    // word-split by zsh, and the login shell of the exec is the server's choice.
    private const val TMUX_CAPPED = "timeout -k 1 2 tmux -u"

    fun serverCommand(tmux: String, expect: ServerExpectation, text: String, approve: Boolean = false, emptyInput: Boolean = false): String =
        build(tmux, expect, text, approve, emptyInput, checkOnly = false)

    /**
     * F5d: the same checks as [serverCommand] with `emptyInput` against an idle session — the last hook event is
     * `stop`/`start`, the pane exists, its input box is empty — and NO key: prints `CR_REPLY IDLE` when all hold, else
     * the token that failed (`STALE|NOHOOKS|NOPANE|DRAFT`). The Jarvis account switch asks it right before it
     * restarts his pane.
     */
    fun idleCheckCommand(tmux: String): String = build(tmux, ServerExpectation.Idle, "", approve = false, emptyInput = true, checkOnly = true)

    /**
     * F5d (rev HIGH-1): [idleCheckCommand], and [then] runs in the SAME shell right after `CR_REPLY IDLE` — never
     * otherwise — with `$P` set to the pane the check read (the first pane of the session, the one every checked reply
     * types into). Nothing can slip between the check and [then] but the few ms of the shell itself.
     */
    fun idleThenCommand(tmux: String, then: String): String =
        build(tmux, ServerExpectation.Idle, "", approve = false, emptyInput = true, checkOnly = true, onIdle = then)

    /** [idleCheckCommand]'s output: null = idle with an empty input, else why not. */
    fun idleCheckResult(output: String?): RefuseReason? {
        val token = output?.lineSequence()?.map { it.trim() }?.lastOrNull { it.startsWith("$SERVER_TOKEN ") }
            ?.removePrefix("$SERVER_TOKEN ")?.substringBefore(' ')
        return when (token) {
            "IDLE" -> null
            "STALE" -> RefuseReason.PROMPT_CHANGED
            "NOHOOKS" -> RefuseReason.NO_HOOKS
            "DRAFT" -> RefuseReason.DRAFT_IN_INPUT
            else -> RefuseReason.NOT_VERIFIABLE
        }
    }

    private fun build(
        tmux: String,
        expect: ServerExpectation,
        text: String,
        approve: Boolean,
        emptyInput: Boolean,
        checkOnly: Boolean,
        onIdle: String? = null,
    ): String {
        val decide = when (expect) {
            is ServerExpectation.Dialog -> {
                val want = sq("${expect.ev} ${expect.atMs}")
                "if [ -z \"\$L\" ]; then W=NOHOOKS; elif [ \"\$L\" = $want ]; then OK=1; else W=STALE; fi"
            }
            is ServerExpectation.Ready ->
                "case \"\$L\" in 'stop '*) OK=1;; '') " +
                    "if [ -n \"\$M\" ] && [ \"\$M\" -ge ${expect.stopEpochSec} ] 2>/dev/null; then OK=1; else W=STALE; fi;; " +
                    "*) W=STALE;; esac"
            // A `start` is a fresh, cleared or resumed session at its prompt (a drift relaunch too).
            ServerExpectation.Idle -> "case \"\$L\" in 'stop '*|'start '*) OK=1;; '') W=NOHOOKS;; *) W=STALE;; esac"
            // After `end` the pane holds a shell: the text would run in bash.
            ServerExpectation.Working ->
                "case \"\$L\" in '') W=NOHOOKS;; 'perm '*|'ask '*|'end '*) W=STALE;; *) OK=1;; esac"
        }
        val name = sq(tmux)
        val keys = sq(tmuxLiteral(text))
        val send = if (approve) {
            "if $TMUX_CAPPED send-keys -t \"\$P\" -l -- $keys \\; send-keys -t \"\$P\" Enter 2>/dev/null; " +
                "then echo '$SERVER_TOKEN SENT'; else echo '$SERVER_TOKEN FAILED'; fi"
        } else {
            // emptyInput (the conversation mode): SENT carries the server's ms right before Enter, so the app reads
            // only a Stop after it as the answer (a wake's Stop still in flight is older)
            val sent = if (emptyInput) "T=\$(date +%s%3N 2>/dev/null); " else ""
            val sentEcho = if (emptyInput) "echo \"$SERVER_TOKEN SENT \$T\"" else "echo '$SERVER_TOKEN SENT'"
            "if $TMUX_CAPPED send-keys -t \"\$P\" -l -- $keys 2>/dev/null; then sleep 0.1; rd; " +
                "if [ \"\$L\" != \"\$L0\" ]; then echo '$SERVER_TOKEN STALE_TYPED'; " +
                "else ${sent}if $TMUX_CAPPED send-keys -t \"\$P\" Enter 2>/dev/null; then $sentEcho; " +
                "else echo '$SERVER_TOKEN FAILED'; fi; fi; else echo '$SERVER_TOKEN FAILED'; fi"
        }
        // tac reads the file from its end: the pane's newest line is found without scanning
        // 20 MB of other panes' history.
        // Every tmux call is capped ([TMUX_CAPPED]): a hung tmux server would otherwise hold the
        // exec until its 8 s budget ran out, read as "may have been sent", and the ledger would
        // keep the prompt answered with nothing typed. A list-panes cut off (exit 124) leaves P
        // empty: NOPANE, nothing sent.
        return "N=\$(printf '%s' $name | base64 -w0); " +
            "E=\"\$HOME/${HookCommands.EVENTS_PATH.removePrefix("~/")}\"; OK=0; W=STALE; " +
            "rd() { L=''; SZ=\$(stat -c %s \"\$E\" 2>/dev/null || echo 0); " +
            "if [ ! -f \"\$HOME/${HookCommands.OFF_SWITCH_PATH.removePrefix("~/")}\" ] && " +
            "[ \"\$SZ\" -lt ${HookCommands.EVENTS_MAX_BYTES - 2048} ]; then " +
            "L=\$(tac \"\$E\" 2>/dev/null | awk -v n=\"\$N\" '\$1==\"claude-remote-ev\" && \$3==n {print \$2\" \"\$4; exit}'); fi; }; " +
            "rd; L0=\$L; " +
            "M=\$(cat \"\$HOME/${MARKER_PATH.removePrefix("~/")}\" ${sq(LEGACY_MARKER_PATH)} 2>/dev/null | " +
            "awk -v n=$name '\$1==\"claude-remote-notify\" && \$2==n && \$3+0>m {m=\$3+0} END{if (m) print m}'); " +
            "$decide; " +
            "if [ \"\$OK\" = 1 ]; then " +
            "P=\$($TMUX_CAPPED list-panes -s -t ${sq("=$tmux")} -F '#{pane_id}' 2>/dev/null | head -n 1); " +
            "if [ -z \"\$P\" ]; then echo '$SERVER_TOKEN NOPANE'; " +
            (if (emptyInput) "elif [ \"\$($TMUX_CAPPED capture-pane -p -e -t \"\$P\" 2>/dev/null | $DRAFT_AWK)\" != 0 ]; then echo '$SERVER_TOKEN DRAFT'; " else "") +
            (if (checkOnly) "else echo '$SERVER_TOKEN IDLE'; ${onIdle?.let { "$it; " }.orEmpty()}fi; " else "else echo '$SERVER_TOKEN SENDING'; $send; fi; ") +
            "else echo \"$SERVER_TOKEN \$W\"; fi"
    }

    /**
     * Prints `0` for an empty input box, `1` for a draft, `-` when no box is on the screen. A copy of the reading in
     * [CloseFacts] (`FACT draft`), spelled with `$` here (that one is a `§` template): the box row is the one right
     * below a `─` rule that starts with `❯`+NBSP; dim (`ESC[2m`, the "Try …" placeholder) and reversed (`ESC[7m`, the
     * cursor) runs are dropped, then the colours, NBSPs and blanks; whatever is left was typed.
     */
    private val DRAFT_AWK = "LC_ALL=C awk -v esc=\"\$(printf '\\033')\" '" +
        "function plain(s) { gsub(esc \"\\\\[[0-9;]*m\", \"\", s); return s } " +
        "{ p = plain(\$0); if (rule && index(p, \"\\342\\235\\257\\302\\240\") == 1) cand = \$0; rule = (index(p, \"\\342\\224\\200\") == 1) } " +
        "END { if (cand == \"\") { print \"-\"; exit } s = cand; sub(/^[^\\342]*\\342\\235\\257\\302\\240/, \"\", s); " +
        "gsub(esc \"\\\\[(0;)?2m[^\" esc \"]*\", \"\", s); gsub(esc \"\\\\[7m[^\" esc \"]*\", \"\", s); " +
        "s = plain(s); gsub(/\\302\\240/, \"\", s); gsub(/[ \\t]/, \"\", s); print (s == \"\" ? 0 : 1) }'"

    /** The server's ms of `CR_REPLY SENT <ms>` (emptyInput), null when it did not say (or not in ms). */
    fun sentAtServerMs(output: String?): Long? =
        output?.lineSequence()?.map { it.trim() }?.lastOrNull { it.startsWith("$SERVER_TOKEN SENT") }
            ?.removePrefix("$SERVER_TOKEN SENT")?.trim()?.takeIf { it.length >= 12 }?.toLongOrNull()

    /**
     * The refusal a watch reply earns before any state is looked at: an old watch build sends
     * no prompt identity at all (it shows "sent" for it, so the phone has to say so), and an
     * identity that does not decode names nothing.
     */
    fun watchRefusal(rawPrompt: String?): RefuseReason? = when {
        rawPrompt == null -> RefuseReason.WATCH_OUTDATED
        PromptFingerprint.decode(rawPrompt) == null -> RefuseReason.NO_PROMPT
        else -> null
    }

    /**
     * null = sent; otherwise why not. Anything unexpected (no output, a failed exec) fails
     * closed; output that stops after SENDING (the exec broke off mid-way) may have been sent.
     */
    fun serverResult(output: String?): RefuseReason? {
        val token = output?.lineSequence()?.map { it.trim() }?.lastOrNull { it.startsWith("$SERVER_TOKEN ") }
            ?.removePrefix("$SERVER_TOKEN ")?.substringBefore(' ')
        return when (token) {
            "SENT" -> null
            "SENDING" -> RefuseReason.MAYBE_SENT
            "STALE" -> RefuseReason.PROMPT_CHANGED
            "STALE_TYPED" -> RefuseReason.TYPED_NOT_SENT
            "NOHOOKS" -> RefuseReason.NO_HOOKS
            "DRAFT" -> RefuseReason.DRAFT_IN_INPUT
            else -> RefuseReason.NOT_VERIFIABLE
        }
    }
}

/**
 * The newest prompt each session announced. Set by every prompt event (also one that raised no
 * alert: the active tab in the foreground, an old replayed `perm`), cleared as soon as the
 * session leaves the prompt states — after WORKING, an APPROVAL with no new event must not
 * revive the key of the dialog that was answered.
 *
 * One dialog can be announced by two sources (hook event and screen detection, see
 * [PromptDialogDedup]); the key of the one held back as "the same dialog" is kept as an alias,
 * so the alert raised by the other one stays answerable.
 */
internal class PromptTokens(private val clock: () -> Long = { System.currentTimeMillis() }) {
    /**
     * [text]: what the prompt asks, as its hook event put it (`perm`: "Tool: description or
     * command", `ask`: "question [option, …]"), null for a prompt only the screen or a Stop marker
     * named. Shown by the Velín inbox; never part of the identity a reply is checked against.
     */
    data class Current(val prompt: PromptFingerprint, val aliases: Set<String> = emptySet(), val text: String? = null) {
        fun names(key: String) = key == prompt.key || key in aliases

        /** The hook event among the names ((ev, atMs)), the primary first. */
        fun hookEvent(): Pair<String, Long>? =
            NotifyKeys.hookEvent(prompt.key) ?: aliases.firstNotNullOfOrNull { NotifyKeys.hookEvent(it) }
    }

    private class Entry(val current: Current, val atMs: Long, val replaced: Entry?)

    private val lock = Any()
    private val tokens = HashMap<String, Entry>()

    /** A session's newest prompt and when it appeared (device clock), for a view that lists them. */
    data class Shown(val current: Current, val atMs: Long)

    private val _shown = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Shown>>(emptyMap())

    /**
     * The same state as [current], published so a list of open prompts (the Velín inbox) redraws
     * when one appears, merges or goes away. Written under [lock] after every change.
     */
    val shown: kotlinx.coroutines.flow.StateFlow<Map<String, Shown>> = _shown

    private fun publish() {
        _shown.value = tokens.mapValues { (_, e) -> Shown(e.current, e.atMs) }
    }

    /** [atMs]: when the prompt appeared, on the device clock (a hook event's time corrected for skew). */
    fun note(sessionId: String, prompt: PromptFingerprint, atMs: Long = clock(), text: String? = null) = synchronized(lock) {
        val prev = tokens[sessionId]
        if (prev?.current?.prompt == prompt) {
            // The same prompt noted again (the alert after the hook's own note): keep it, but a
            // text the first note lacked is still worth having.
            if (text != null && prev.current.text == null) {
                tokens[sessionId] = Entry(prev.current.copy(text = text), prev.atMs, prev.replaced)
                publish()
            }
            return@synchronized
        }
        tokens[sessionId] = Entry(Current(prompt, text = text), atMs, prev?.let { Entry(it.current, it.atMs, null) })
        publish()
    }

    /**
     * The dedup held back the event [key] as the same dialog an alert already announced.
     * - Hook first: the screen's [key] joins the hook's prompt.
     * - Screen first: the hook was noted on arrival and replaced the screen's prompt; that one
     *   comes back as an alias, but only when the hook fired no later than [SAME_DIALOG_SLACK_MS]
     *   after the screen saw a dialog. A hook that fired later belongs to a dialog that came after
     *   the screen's one — the 15 s dedup window alone cannot tell them apart.
     */
    fun sameDialog(sessionId: String, key: String) = synchronized(lock) {
        val e = tokens[sessionId] ?: return@synchronized
        if (!e.current.prompt.kind.let { it == PromptKind.PERMISSION || it == PromptKind.QUESTION }) return@synchronized
        val prev = e.replaced
        val merged = when {
            e.current.prompt.key != key -> e.current.copy(aliases = e.current.aliases + key)
            prev != null && prev.current.prompt.kind.let { it == PromptKind.PERMISSION || it == PromptKind.QUESTION } &&
                e.atMs <= prev.atMs + SAME_DIALOG_SLACK_MS ->
                e.current.copy(aliases = e.current.aliases + prev.current.prompt.key + prev.current.aliases)
            else -> return@synchronized
        }
        tokens[sessionId] = Entry(merged, e.atMs, e.replaced)
        publish()
    }

    fun onActivity(sessionId: String, activity: SessionActivity) {
        if (activity == SessionActivity.WAITING_FOR_INPUT || activity == SessionActivity.APPROVAL_NEEDED) return
        synchronized(lock) { if (tokens.remove(sessionId) != null) publish() }
    }

    fun current(sessionId: String): Current? = synchronized(lock) { tokens[sessionId]?.current }

    fun forget(sessionId: String) { synchronized(lock) { if (tokens.remove(sessionId) != null) publish() } }

    companion object {
        /**
         * How much later than the screen's sighting a hook may have fired and still be the same
         * dialog. The hook fires before the dialog is drawn and the screen sees it only after it
         * went quiet, so the true order is always hook first; this only absorbs clock-skew error.
         */
        const val SAME_DIALOG_SLACK_MS = 1_000L
    }
}

/**
 * One answer per prompt from outside the app. The server cannot tell an answered permission
 * from an open one (approving writes no event), so a second "Ano" with the same identity — a
 * repeat after "Nepotvrzeno", or the phone and the watch at once — would pass its check and
 * land in whatever comes next. A prompt is taken before the exec and stays taken when the keys
 * went out (or may have); any other outcome releases it for another try.
 */
internal class ReplyLedger(private val capacity: Int = 256) {
    private val lock = Any()
    private val inFlight = HashSet<String>()
    private val answered = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean = size > capacity
    }

    /** false when [key] is being answered right now or already was. */
    fun begin(key: String): Boolean = synchronized(lock) {
        if (key in inFlight || answered.containsKey(key)) false else inFlight.add(key)
    }

    /**
     * [why]: the outcome of the send that [begin] allowed (null = sent). Text left typed in the
     * input stays taken too: another try would type it a second time.
     */
    fun finish(key: String, why: RefuseReason?) {
        synchronized(lock) {
            inFlight.remove(key)
            if (why == null || why == RefuseReason.MAYBE_SENT || why == RefuseReason.TYPED_NOT_SENT) answered[key] = Unit
        }
    }
}
