package com.clauderemote.voice.conversation

import com.clauderemote.session.service.RefuseReason
import com.clauderemote.voice.speakableFromMarkdown

/**
 * Jarvis' conversation mode (F5a, 2026-11-09): a half-duplex loop between Lukáš's voice and Jarvis' pane.
 *
 *     LISTENING ──heard──▶ SENDING ──sent──▶ THINKING ──Jarvis' Stop──▶ SPEAKING ──done──▶ LISTENING …
 *
 * The microphone is closed whenever anything is read aloud (half-duplex: the headset would hear the TTS), and the
 * TTS never talks over the microphone. This file is the pure part — states, events, effects and the rules between
 * them — so every transition and timeout is a unit test on the desktop; [JarvisTalkDriver] runs it against the
 * platform's STT, TTS and the app's path into Jarvis.
 *
 * Safety (decisions 14d/14e): the loop only ever produces [TalkEffect.Send] for Jarvis' own pane (the link knows no
 * other target) and only as plain text prefixed `[hlas] `; it has no way to answer a permission or a question of any
 * session. Whether the text may go in at all is decided by the link (ReplyGuard: Jarvis idle at his prompt, nothing
 * typed in his input); a refusal is said aloud and the loop listens again — nothing is queued for later.
 */
enum class TalkPhase { OFF, LISTENING, SENDING, THINKING, SPEAKING }

/** Why a conversation ended; [sentence] is said once before the mode goes off (null = silently). */
enum class TalkEnd(val sentence: String?) {
    /** "konec", "stop", "díky, to je vše". */
    WORD("Dobře, končím."),
    /** The "Ukončit" button or the notification action: the user is looking, no sentence needed. */
    BUTTON(null),
    SILENCE("Dvě minuty ticho, končím konverzaci."),
    ROUTE_LOST("Spojení s Jarvisem se ztratilo, končím."),
    MIC("Mikrofon nebo přepis řeči nefunguje, končím."),
    /**
     * A reading that never ends: a permanent focus loss (Spotify starts) or a pause from the player's notification
     * leaves it paused for good, the microphone closed. [TalkMachine.SPEAK_MAX_MS] ends the mode instead of hanging.
     */
    STALLED("Čtení se zaseklo, končím konverzaci."),
}

enum class TalkTone {
    /** The microphone opens. */
    LISTEN,
    /** Jarvis has been thinking for [TalkMachine.CUE_MS]: "moment". */
    WAIT,
}

data class TalkState(
    val phase: TalkPhase = TalkPhase.OFF,
    /** When the current phase began (device clock). */
    val sinceMs: Long = 0,
    /** The silence clock: the last words heard, or the end of the last thing read aloud. */
    val quietSinceMs: Long = 0,
    /** A prompt went in and Jarvis' answer to it has not been read yet (also while listening after the 60 s notice). */
    val awaiting: Boolean = false,
    /** When the last prompt that went in was handed to the link (device clock). */
    val sentAtMs: Long = 0,
    /**
     * When the server pressed Enter for it (server clock, ms; `CR_REPLY SENT <ms>`), null = unknown. Only a Stop in a
     * LATER second is its answer — the identity of our turn, not the device's guess of the time.
     */
    val sentServerMs: Long? = null,
    /** When the prompt now being sent was handed over (device clock); becomes [sentAtMs] once it went in. */
    val sendingAtMs: Long = 0,
    /** Finished turns that arrived while the send was still out (newest last): decided when its result is known. */
    val heldAnswers: List<TalkEvent.Answer> = emptyList(),
    /** The utterance was asked to end now ([TalkEffect.FinishListening]); a dead STT never answers that. */
    val finishingSinceMs: Long? = null,
    /** The [TalkTone.WAIT] cue of this THINKING was given. */
    val cued: Boolean = false,
    /** Since when the route to Jarvis has been down (null = up). */
    val routeDownSinceMs: Long? = null,
    /** STT failures in a row; [TalkMachine.MAX_STT_FAILURES] ends the mode. */
    val sttFailures: Int = 0,
    /** Jarvis' answer that came while a notice was being read; read right after it. */
    val queuedAnswer: String? = null,
    /** SPEAKING the closing sentence of this end. */
    val ending: TalkEnd? = null,
    /** OFF after a conversation: how it ended (the UI says it). */
    val ended: TalkEnd? = null,
    /** The last utterance sent (the UI shows it). */
    val heard: String? = null,
    /** The last sentence the mode said itself (a refusal, "Jarvis zatím neodpověděl"). */
    val notice: String? = null,
)

sealed interface TalkEvent {
    data object Start : TalkEvent
    /** The STT ended an utterance; [text] may be blank (no speech before its guard ran out). */
    data class Heard(val text: String) : TalkEvent
    data class SttFailed(val why: String) : TalkEvent
    /** The link's answer to [TalkEffect.Send]. */
    data class SendDone(val result: TalkSendResult) : TalkEvent
    /**
     * A finished turn of Jarvis: [text] = the body the Stop notification chose, [atMs] = when it ended on the device
     * clock (server time + measured skew), [serverSec] = the Stop marker's own epoch second (server clock).
     */
    data class Answer(val text: String?, val atMs: Long?, val serverSec: Long? = null) : TalkEvent
    /** What [TalkEffect.Speak] started has been read out (or failed: the loop must go on either way). */
    data object SpeechDone : TalkEvent
    /** The headset's play/pause or the in-app "Mluvit" button: interrupt the reading / start (or finish) talking. */
    data object Button : TalkEvent
    /** "Ukončit". */
    data object Stop : TalkEvent
    /** Every [JarvisTalkDriver.TICK_MS]; [routeOk] = Jarvis' session can be reached right now. */
    data class Tick(val routeOk: Boolean) : TalkEvent
}

sealed interface TalkSendResult {
    /** [serverAtMs]: when the server pressed Enter (its clock, ms), null = it did not say. */
    data class Sent(val serverAtMs: Long? = null) : TalkSendResult
    /** It may have gone in (the exec broke off after the keys): wait for an answer, but say so. */
    data class Maybe(val sentence: String) : TalkSendResult
    data class Refused(val sentence: String) : TalkSendResult
}

sealed interface TalkEffect {
    /** Open the microphone for one utterance (after [delayMs]: a retry after an STT failure). */
    data class Listen(val delayMs: Long = 0) : TalkEffect
    /** End the utterance now and report what was heard ([TalkEvent.Heard]). */
    data object FinishListening : TalkEffect
    /** Close the microphone; whatever it heard does not count. */
    data object CancelListening : TalkEffect
    data class Send(val prompt: String) : TalkEffect
    /** The send still running is abandoned ("Ukončit" while sending): no keys after the end, as far as it can help. */
    data object CancelSend : TalkEffect
    data class Speak(val text: String) : TalkEffect
    data object StopSpeaking : TalkEffect
    data class Tone(val tone: TalkTone) : TalkEffect
    data class Ended(val why: TalkEnd) : TalkEffect
}

data class TalkStep(val state: TalkState, val effects: List<TalkEffect> = emptyList())

object TalkMachine {
    /** "Moment" after this long without an answer. */
    const val CUE_MS = 3_000L
    /** Then "Jarvis zatím neodpověděl" and back to listening (the answer is still read when it comes). */
    const val GIVE_UP_MS = 60_000L
    /** No words for this long while listening ends the mode. */
    const val SILENCE_MS = 120_000L
    /** A route down this long is lost (a reconnect blip is not). */
    const val ROUTE_GRACE_MS = 5_000L
    const val MAX_STT_FAILURES = 3
    const val STT_RETRY_MS = 1_000L
    /** A closing sentence whose TTS never reports done does not keep the mode on. */
    const val ENDING_MAX_MS = 20_000L
    /**
     * An answer may carry a Stop time up to this much before the prompt went in (the device↔server skew is measured,
     * not exact). A turn that ended earlier than that is an older one, not the answer.
     */
    const val ANSWER_SLACK_MS = 1_500L
    /** After "Odeslat" the STT has this long to report the utterance; a dead socket then counts as an STT failure. */
    const val FINISH_TIMEOUT_MS = 5_000L
    /** Nothing read by the mode takes this long (Jarvis answers in a sentence or two); longer = it is stuck. */
    const val SPEAK_MAX_MS = 5 * 60_000L
    /** At most this many turns are held during one send. */
    const val HELD_MAX = 4

    const val NOT_ANSWERED = "Jarvis zatím neodpověděl, poslouchám dál."
    const val NO_TEXT = "Jarvis odpověděl, ale text nemám. Je v aplikaci."

    fun reduce(s: TalkState, e: TalkEvent, now: Long): TalkStep {
        if (e is TalkEvent.Start) {
            return if (s.phase != TalkPhase.OFF) TalkStep(s)
            else TalkStep(
                TalkState(phase = TalkPhase.LISTENING, sinceMs = now, quietSinceMs = now),
                listOf(TalkEffect.Tone(TalkTone.LISTEN), TalkEffect.Listen()),
            )
        }
        // Late callbacks (a final transcript, a send result) after the end change nothing.
        if (s.phase == TalkPhase.OFF) return TalkStep(s)
        return when (e) {
            TalkEvent.Stop -> TalkStep(
                off(s, TalkEnd.BUTTON),
                listOf(TalkEffect.CancelSend, TalkEffect.CancelListening, TalkEffect.StopSpeaking, TalkEffect.Ended(TalkEnd.BUTTON)),
            )
            is TalkEvent.Tick -> tick(s, e.routeOk, now)
            is TalkEvent.Heard -> heard(s, e.text, now)
            is TalkEvent.SttFailed -> if (s.phase != TalkPhase.LISTENING) TalkStep(s) else sttFailed(s, now, emptyList())
            is TalkEvent.SendDone -> if (s.phase != TalkPhase.SENDING) TalkStep(s) else sendDone(s, e.result, now)
            is TalkEvent.Answer -> answer(s, e, now)
            TalkEvent.SpeechDone -> speechDone(s, now)
            TalkEvent.Button -> button(s, now)
            TalkEvent.Start -> TalkStep(s)
        }
    }

    private fun heard(s: TalkState, raw: String, now: Long): TalkStep {
        if (s.phase != TalkPhase.LISTENING) return TalkStep(s)
        val text = raw.trim()
        // Nothing said before the STT's own guard ran out: listen again; the silence clock keeps running.
        if (text.isEmpty()) return TalkStep(s.copy(sttFailures = 0, finishingSinceMs = null), listOf(TalkEffect.Listen()))
        if (TalkWords.isEnd(text)) return end(s.copy(heard = text, finishingSinceMs = null), TalkEnd.WORD, now)
        return TalkStep(
            s.copy(
                phase = TalkPhase.SENDING, sinceMs = now, quietSinceMs = now, sendingAtMs = now, heard = text,
                sttFailures = 0, heldAnswers = emptyList(), finishingSinceMs = null,
            ),
            listOf(TalkEffect.Send(TalkWords.prompt(text))),
        )
    }

    private fun sttFailed(s: TalkState, now: Long, before: List<TalkEffect>): TalkStep {
        val n = s.sttFailures + 1
        val st = s.copy(sttFailures = n, finishingSinceMs = null)
        return if (n >= MAX_STT_FAILURES) end(st, TalkEnd.MIC, now)
        else TalkStep(st, before + TalkEffect.Listen(STT_RETRY_MS))
    }

    /**
     * The send's result decides the turns that ended while it was out ([TalkState.heldAnswers]):
     * - this prompt's answer: read only when the prompt went in and the turn is later than its Enter;
     * - the answer to the prompt BEFORE it, still awaited after the 60 s notice: read first (it ended before this
     *   Enter — Jarvis was idle when the keys went in), this prompt's own answer is then still awaited;
     * - anything else (a wake, a mail) is dropped; a refused send keeps only an answer to the prompt before.
     */
    private fun sendDone(s: TalkState, r: TalkSendResult, now: Long): TalkStep {
        val held = s.heldAnswers
        val base = s.copy(heldAnswers = emptyList())
        fun older(newer: TalkState) = if (s.awaiting) held.lastOrNull { !accepts(newer, it) && accepts(s, it) } else null
        return when (r) {
            is TalkSendResult.Sent -> {
                val st = base.copy(
                    phase = TalkPhase.THINKING, sinceMs = now, awaiting = true, cued = false,
                    sentAtMs = s.sendingAtMs, sentServerMs = r.serverAtMs,
                )
                val mine = held.lastOrNull { accepts(st, it) }
                val old = older(st)
                when {
                    old != null -> TalkStep(
                        st.copy(phase = TalkPhase.SPEAKING, awaiting = mine == null, queuedAnswer = mine?.let { answerText(it) }),
                        listOf(TalkEffect.Speak(answerText(old))),
                    )
                    mine != null -> speak(st, mine, now)
                    else -> TalkStep(st)
                }
            }
            is TalkSendResult.Maybe -> {
                val st = base.copy(awaiting = true, sentAtMs = s.sendingAtMs, sentServerMs = null)
                val mine = held.lastOrNull { accepts(st, it) }
                val queued = listOfNotNull(older(st), mine).map { answerText(it) }
                val step = say(st, r.sentence, now)
                if (queued.isEmpty()) step
                else step.copy(state = step.state.copy(awaiting = mine == null, queuedAnswer = queued.joinToString(" ")))
            }
            is TalkSendResult.Refused -> {
                val step = say(base, r.sentence, now)
                val old = if (s.awaiting) held.lastOrNull { accepts(s, it) } else null
                if (old != null) step.copy(state = step.state.copy(awaiting = false, queuedAnswer = answerText(old)))
                else step
            }
        }
    }

    /**
     * Whether [e] is the turn our last prompt started — not a `[jarvis wake]` or a mail Jarvis finished before it.
     * With the server's Enter time: a Stop marker in a later second (an answer takes more than the rest of the second
     * the Enter fell in). Without it: the device-clock bound with [ANSWER_SLACK_MS]. With neither time: not ours.
     */
    internal fun accepts(s: TalkState, e: TalkEvent.Answer): Boolean {
        val sentServer = s.sentServerMs
        if (sentServer != null) return e.serverSec != null && e.serverSec > sentServer / 1000
        return e.atMs != null && e.atMs >= s.sentAtMs - ANSWER_SLACK_MS
    }

    private fun answerText(e: TalkEvent.Answer) = e.text?.let { speakableFromMarkdown(it) }?.takeIf { it.isNotBlank() } ?: NO_TEXT

    private fun speak(s: TalkState, e: TalkEvent.Answer, now: Long) =
        TalkStep(s.copy(phase = TalkPhase.SPEAKING, sinceMs = now, awaiting = false), listOf(TalkEffect.Speak(answerText(e))))

    private fun answer(s: TalkState, e: TalkEvent.Answer, now: Long): TalkStep {
        // the send is still out: whether this is our answer is known only with its result
        if (s.phase == TalkPhase.SENDING) return TalkStep(s.copy(heldAnswers = (s.heldAnswers + e).takeLast(HELD_MAX)))
        // Only the turn our prompt started: not a wake or a mail Jarvis handled before it, nor one after we read it.
        if (!s.awaiting || !accepts(s, e)) return TalkStep(s)
        val text = answerText(e)
        return when (s.phase) {
            // a notice is being read: the answer follows it
            TalkPhase.SPEAKING -> TalkStep(s.copy(awaiting = false, queuedAnswer = if (s.ending == null) text else null))
            TalkPhase.LISTENING -> TalkStep(
                s.copy(phase = TalkPhase.SPEAKING, sinceMs = now, awaiting = false),
                listOf(TalkEffect.CancelListening, TalkEffect.Speak(text)),
            )
            else -> TalkStep(s.copy(phase = TalkPhase.SPEAKING, sinceMs = now, awaiting = false), listOf(TalkEffect.Speak(text)))
        }
    }

    private fun speechDone(s: TalkState, now: Long): TalkStep {
        if (s.phase != TalkPhase.SPEAKING) return TalkStep(s)
        s.ending?.let { why -> return TalkStep(off(s, why), listOf(TalkEffect.Ended(why))) }
        s.queuedAnswer?.let { q -> return TalkStep(s.copy(sinceMs = now, queuedAnswer = null), listOf(TalkEffect.Speak(q))) }
        return listen(s, now)
    }

    private fun button(s: TalkState, now: Long): TalkStep = when (s.phase) {
        TalkPhase.SPEAKING -> when {
            s.ending != null -> TalkStep(off(s, s.ending), listOf(TalkEffect.StopSpeaking, TalkEffect.Ended(s.ending)))
            // a notice is cut short, Jarvis' answer waiting behind it is read now
            s.queuedAnswer != null -> TalkStep(
                s.copy(sinceMs = now, queuedAnswer = null),
                listOf(TalkEffect.StopSpeaking, TalkEffect.Speak(s.queuedAnswer)),
            )
            else -> listen(s, now, listOf(TalkEffect.StopSpeaking))
        }
        // "I'm done": the utterance ends now instead of after the silence pause
        TalkPhase.LISTENING -> TalkStep(s.copy(finishingSinceMs = s.finishingSinceMs ?: now), listOf(TalkEffect.FinishListening))
        // he wants to add something; Jarvis' answer is still read when it comes
        TalkPhase.THINKING -> listen(s, now)
        TalkPhase.SENDING, TalkPhase.OFF -> TalkStep(s)
    }

    private fun tick(s: TalkState, routeOk: Boolean, now: Long): TalkStep {
        if (s.ending != null) {
            return if (now - s.sinceMs >= ENDING_MAX_MS) TalkStep(off(s, s.ending), listOf(TalkEffect.StopSpeaking, TalkEffect.Ended(s.ending)))
            else TalkStep(s)
        }
        val down = if (routeOk) null else s.routeDownSinceMs ?: now
        if (down != null && now - down >= ROUTE_GRACE_MS) return end(s, TalkEnd.ROUTE_LOST, now)
        val st = s.copy(routeDownSinceMs = down)
        return when (st.phase) {
            TalkPhase.THINKING -> when {
                now - st.sinceMs >= GIVE_UP_MS -> say(st, NOT_ANSWERED, now)
                !st.cued && now - st.sinceMs >= CUE_MS -> TalkStep(st.copy(cued = true), listOf(TalkEffect.Tone(TalkTone.WAIT)))
                else -> TalkStep(st)
            }
            // a reading paused for good (permanent focus loss, the notification's pause): end, never hang mic-closed
            TalkPhase.SPEAKING -> if (now - st.sinceMs >= SPEAK_MAX_MS) end(st, TalkEnd.STALLED, now) else TalkStep(st)
            TalkPhase.LISTENING -> when {
                // "Odeslat" got no answer from the STT (a dead socket): a failure, and the microphone opens anew
                st.finishingSinceMs != null && now - st.finishingSinceMs >= FINISH_TIMEOUT_MS ->
                    sttFailed(st, now, listOf(TalkEffect.CancelListening))
                now - st.quietSinceMs >= SILENCE_MS -> end(st, TalkEnd.SILENCE, now)
                else -> TalkStep(st)
            }
            else -> TalkStep(st)
        }
    }

    /** Read a sentence of the mode's own, then listen again. */
    private fun say(s: TalkState, sentence: String, now: Long) = TalkStep(
        s.copy(phase = TalkPhase.SPEAKING, sinceMs = now, notice = sentence),
        listOf(TalkEffect.Speak(sentence)),
    )

    private fun listen(s: TalkState, now: Long, before: List<TalkEffect> = emptyList()) = TalkStep(
        s.copy(phase = TalkPhase.LISTENING, sinceMs = now, quietSinceMs = now, finishingSinceMs = null),
        before + listOf(TalkEffect.Tone(TalkTone.LISTEN), TalkEffect.Listen()),
    )

    /** End with [why]'s sentence said once (the microphone closes first), or at once when it has none. */
    private fun end(s: TalkState, why: TalkEnd, now: Long): TalkStep {
        val stopAll = listOf(TalkEffect.CancelSend, TalkEffect.CancelListening, TalkEffect.StopSpeaking)
        val sentence = why.sentence ?: return TalkStep(off(s, why), stopAll + TalkEffect.Ended(why))
        return TalkStep(
            s.copy(phase = TalkPhase.SPEAKING, sinceMs = now, ending = why, notice = sentence, queuedAnswer = null, heldAnswers = emptyList()),
            stopAll + TalkEffect.Speak(sentence),
        )
    }

    private fun off(s: TalkState, why: TalkEnd) = TalkState(ended = why, heard = s.heard, notice = s.notice)
}

object TalkWords {
    /** What Jarvis' CLAUDE.md and the hook (`cr-mail prompt-seen`) recognise as a spoken message. */
    const val PREFIX = "[hlas] "
    /**
     * The prompt's size as the reply command carries it: UTF-8, single-quoted with every `'` as `'\''` (4 bytes), and
     * a trailing `;` escaped for tmux. The cap keeps the whole exec argument under 4 KB (ExecArgumentSizeTest): the
     * command around it is ~1.8 KB.
     */
    const val MAX_QUOTED_BYTES = 1_900

    /**
     * Control and format characters (ESC, BEL, DEL, C1, bidi and zero-width marks) and the Unicode line/paragraph
     * separators: `send-keys -l` would type them into Jarvis' pane as they are.
     */
    private val INVISIBLE = Regex("[\\p{Cc}\\p{Cf}\\u2028\\u2029]")
    private val SPACES = Regex("\\s+")

    /** One line of plain text after [PREFIX], cut (on a character boundary) to [MAX_QUOTED_BYTES]. */
    fun prompt(heard: String): String {
        val clean = heard.replace(INVISIBLE, " ").replace(SPACES, " ").trim()
        return fit(PREFIX + clean)
    }

    private fun cost(c: Char): Int = when {
        c == '\'' -> 4
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        c.isSurrogate() -> 2 // a pair is 4 bytes: 2 + 2
        else -> 3
    }

    /** UTF-8 bytes of [s] single-quoted for the shell (`'…'`, `'` as `'\''`), plus 1 for a tmux-escaped final `;`. */
    fun quotedBytes(s: String): Int = 2 + s.sumOf { cost(it) } + if (s.endsWith(";")) 1 else 0

    private fun fit(s: String): String {
        if (quotedBytes(s) <= MAX_QUOTED_BYTES) return s
        var used = 3 // the quotes, and room for an escaped final `;`
        var end = 0
        while (end < s.length) {
            val step = if (s[end].isHighSurrogate() && end + 1 < s.length) 2 else 1
            val c = (0 until step).sumOf { cost(s[end + it]) }
            if (used + c > MAX_QUOTED_BYTES) break
            used += c
            end += step
        }
        return s.substring(0, end).trimEnd()
    }

    /** Whole utterances that end the mode ("Jarvisi, konec." too); a sentence that merely contains one does not. */
    private val END = setOf(
        "konec", "stop", "stopka", "konec konverzace", "ukonci konverzaci", "ukonci to", "to je vse",
        "dik to je vse", "diky to je vse", "dekuji to je vse", "dekuju to je vse", "dekuji", "dekuju",
    )

    fun isEnd(heard: String): Boolean {
        var n = normalize(heard)
        for (call in listOf("jarvisi ", "jarvis ")) n = n.removePrefix(call)
        return n in END
    }

    private const val FROM = "áäčďéěëíňóöřšťúůüýž"
    private const val TO = "aacdeeeinoorstuuuyz"

    /** Lower case, no diacritics, punctuation as spaces, one space between words. */
    fun normalize(s: String): String {
        val b = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val i = FROM.indexOf(ch)
            val c = if (i >= 0) TO[i] else ch
            b.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return b.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    const val NO_TAB = "Jarvis nemá v aplikaci otevřenou session, neposláno."

    /** The sentence a refused send is read as: short, and it says that nothing was sent (or that it may have been). */
    fun refusal(why: RefuseReason): TalkSendResult = when (why) {
        RefuseReason.MAYBE_SENT -> TalkSendResult.Maybe("Nevím, jestli to odešlo. Počkám na Jarvisovu odpověď.")
        RefuseReason.TYPED_NOT_SENT -> TalkSendResult.Refused("Text je napsaný, ale neodeslaný. Dokonči to v aplikaci.")
        RefuseReason.DRAFT_IN_INPUT -> TalkSendResult.Refused("Jarvis má ve vstupu rozepsaný text, neposláno.")
        RefuseReason.NOT_CONNECTED -> TalkSendResult.Refused("Jarvis není připojený, neposláno.")
        RefuseReason.NO_HOOKS, RefuseReason.NOT_VERIFIABLE -> TalkSendResult.Refused("Stav Jarvise nejde ověřit, neposláno.")
        else -> TalkSendResult.Refused("Jarvis teď nemůže přijmout, zkus to za chvíli.")
    }
}
