package com.clauderemote.voice

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.clauderemote.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Splices a dictated [phrase] between the text that was before and after the
 * caret when dictation started, and reports where the caret ends up (just
 * after the inserted words). Anchors are fixed at start so streaming partials
 * keep rewriting the same span instead of walking the cursor forward.
 */
internal data class DictationSplice(val text: String, val caret: Int)

internal fun spliceDictation(before: String, after: String, phrase: String): DictationSplice {
    val head = when {
        phrase.isBlank() -> before
        before.isBlank() -> phrase
        before.endsWith(' ') || before.endsWith('\n') -> before + phrase
        else -> "$before $phrase"
    }
    return DictationSplice(head + after, head.length)
}

@Composable
actual fun MicButton(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier,
    tint: Color,
    autoStartSignal: Int,
    onListeningChange: (Boolean) -> Unit,
) {
    // Soniox is the only desktop-reachable backend (no local model, no
    // assumption that a self-hosted STT server is routable from here), so
    // without its key there is nothing a tap could do but fail: render nothing
    // until Settings → Voice has one, rather than a button that can only ever
    // report an error. Read once per composition — the prompt input
    // recomposes on every keystroke, and the key lives in a file.
    val apiKey = remember { desktopSonioxApiKey() }
    if (apiKey.isBlank()) return

    val scope = rememberCoroutineScope()
    val onValueChangeState = rememberUpdatedState(onValueChange)
    val valueState = rememberUpdatedState(value)
    var listening by remember { mutableStateOf(false) }
    var dictation by remember { mutableStateOf<DesktopDictation?>(null) }
    // Last text WE emitted — lets a late partial/final tell our own updates
    // apart from the user sending, clearing, or typing, so it can't re-inject
    // text the user has already moved on from.
    var lastEmitted by remember { mutableStateOf<String?>(null) }
    val sessionId = remember { AtomicInteger(0) }

    fun dictationEmitter(): (String) -> Unit {
        val v = valueState.value
        val len = v.text.length
        val before = v.text.substring(0, v.selection.min.coerceIn(0, len))
        val after = v.text.substring(v.selection.max.coerceIn(0, len))
        // Fresh dictation: drop the marker from any previous session, or the
        // guard below would reject every emit of this one.
        lastEmitted = null
        return fun(phrase: String) {
            if (lastEmitted != null && valueState.value.text != lastEmitted) return
            val spliced = spliceDictation(before, after, phrase)
            lastEmitted = spliced.text
            onValueChangeState.value(TextFieldValue(spliced.text, TextRange(spliced.caret)))
        }
    }

    /**
     * User asked to stop. The session is deliberately NOT invalidated: stop
     * asks the server to settle what it already heard, and that final still
     * belongs in the field — pressing stop must not throw away the last words.
     */
    fun stopDictation() {
        dictation?.stop()
        dictation = null
        listening = false
    }

    /**
     * The field moved out from under a running dictation (send cleared it, the
     * X button, or hand editing). Here the trailing final is unwanted, so the
     * session is invalidated to make sure it can never re-inject the old text.
     */
    fun cancelDictation() {
        dictation?.stop()
        dictation = null
        sessionId.incrementAndGet()
        listening = false
    }

    fun startListening() {
        val emit = dictationEmitter()
        val mySession = sessionId.incrementAndGet()
        // Callbacks come off OkHttp's reader thread and the silence timer;
        // hop to the composition's dispatcher before touching any state.
        fun onMain(block: () -> Unit) { scope.launch { block() } }
        val d = DesktopDictation(
            apiKey = apiKey,
            silenceMs = desktopDictationSilenceMs(),
            onPartial = { phrase -> onMain { if (sessionId.get() == mySession) emit(phrase) } },
            onFinal = { phrase ->
                onMain {
                    if (sessionId.get() != mySession) return@onMain
                    sessionId.incrementAndGet() // invalidate any trailing callback
                    emit(phrase)
                    dictation?.stop()
                    dictation = null
                    listening = false
                }
            },
            onError = { msg ->
                onMain {
                    if (sessionId.get() != mySession) return@onMain
                    sessionId.incrementAndGet()
                    // No toast surface on desktop; the shipped log is where a
                    // "dictation does nothing" report gets diagnosed.
                    FileLogger.log("DesktopStt", "dictation error: $msg")
                    dictation?.stop()
                    dictation = null
                    listening = false
                }
            },
        )
        dictation = d
        d.start()
        listening = true
    }

    DisposableEffect(Unit) {
        onDispose {
            dictation?.stop()
            dictation = null
        }
    }

    // The user sent, cleared, or edited the field mid-dictation: cancel, so a
    // trailing partial can't put the old text back.
    LaunchedEffect(value.text) {
        if (listening && lastEmitted != null && value.text != lastEmitted) {
            cancelDictation()
            lastEmitted = null
        }
    }

    val onListeningChangeState = rememberUpdatedState(onListeningChange)
    LaunchedEffect(listening) { onListeningChangeState.value(listening) }

    // Hands-free start. The desktop wake word is a no-op today, so this never
    // fires yet; it is here so the contract holds if one ever lands.
    LaunchedEffect(autoStartSignal) {
        if (autoStartSignal > 0 && !listening) startListening()
    }

    IconButton(
        onClick = { if (listening) stopDictation() else startListening() },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (listening) "Stop dictation" else "Dictate",
            tint = tint,
        )
    }
}

@Composable
actual fun SpeakerButton(
    text: String,
    modifier: Modifier,
    tint: Color,
) {
    val scope = rememberCoroutineScope()
    var speaking by remember { mutableStateOf(false) }
    val textState = rememberUpdatedState(text)
    val speakingState = rememberUpdatedState(speaking)

    DisposableEffect(Unit) {
        onDispose {
            // Only ours: the process is app-wide, so an unconditional stop here
            // would silence whichever card is actually reading when this one
            // scrolls out of the list.
            if (speakingState.value) DesktopSpeech.stop()
        }
    }

    IconButton(
        onClick = {
            if (speaking) {
                // Killing the process makes speakBlocking return, which clears
                // `speaking` below — one place resets the state, always.
                DesktopSpeech.stop()
                return@IconButton
            }
            // Read the words, not the markdown syntax (*, `, #, …).
            val payload = speakableFromMarkdown(textState.value)
            if (payload.isBlank()) return@IconButton
            val ratePct = desktopTtsSpeechRatePct()
            speaking = true
            scope.launch {
                // The failure reason is logged inside DesktopSpeech (once for a
                // machine with no synthesiser at all); there is no toast surface
                // on desktop, so nothing is reported to the user beyond the
                // button going quiet.
                withContext(Dispatchers.IO) { DesktopSpeech.speakBlocking(payload, ratePct) }
                // MUST clear on every path — a failed read that left the button
                // stuck "speaking" would send the next tap into the stop branch,
                // and read-aloud would silently die until it was tapped an even
                // number of times. That is a real bug the Android button hit.
                speaking = false
            }
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (speaking) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
            contentDescription = if (speaking) "Stop reading" else "Read aloud",
            tint = tint,
        )
    }
}
