package com.clauderemote.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import android.speech.RecognitionListener
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.clauderemote.model.SttEngine
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

// TTS engine and main-thread helpers live in Tts.android.kt; recognizer helper
// is shared with VoiceMode.android.kt via the same package.

// Recognizer construction lives in Recognizer.android.kt
// (`createCzechRecognizerSmart`) — single source of truth across the
// dictation button and voice mode.

private fun appendDictated(base: String, addition: String): String {
    if (addition.isBlank()) return base
    if (base.isBlank()) return addition
    val needsSpace = !base.endsWith(' ') && !base.endsWith('\n')
    return if (needsSpace) "$base $addition" else base + addition
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
    val context = LocalContext.current
    val srAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    val whisperReady = WhisperModelManager.isModelReady(context)
    val engine = remember { selectedSttEngine(context) }
    // Render nothing only when no backend is usable — otherwise show the
    // button so the user can tap and see a concrete error (model missing,
    // permission denied, etc.) instead of being unable to interact at all.
    // The Server / Soniox engines only need config (URL / API key), so they
    // always keep the button.
    if (engine != SttEngine.SERVER && engine != SttEngine.SONIOX && !srAvailable && !whisperReady) return

    val onValueChangeState = rememberUpdatedState(onValueChange)
    val valueState = rememberUpdatedState(value)

    // Last text WE emitted from dictation — lets us tell our own updates apart
    // from an external change to the field (send clears it, the X button, or
    // the user editing) so a late streaming partial can't re-inject text after
    // the user has moved on.
    var lastEmitted by remember { mutableStateOf<String?>(null) }

    // Captures the caret/selection at dictation start and returns a sink that
    // splices each (partial or final) phrase in AT THAT POINT — replacing any
    // selected text — leaving the caret just after the inserted words. Fixed
    // anchors (not the live caret) so streaming partials keep rewriting the
    // same span instead of walking the cursor forward.
    fun dictationEmitter(): (String) -> Unit {
        val v = valueState.value
        val len = v.text.length
        val before = v.text.substring(0, v.selection.min.coerceIn(0, len))
        val after = v.text.substring(v.selection.max.coerceIn(0, len))
        // Fresh dictation: clear the stale marker from any PREVIOUS session.
        // Otherwise, after you dictated + sent (field cleared to ""), lastEmitted
        // still held the old sent text, so the guard below ("field != lastEmitted
        // → bail") blocked EVERY new dictation's emits until the field happened to
        // match again — dictation appeared to "just stop working" after a send.
        // Late-callback protection now rests on the per-session id (see the
        // sessionId bump in onPartial/onFinal), which is the correct mechanism.
        lastEmitted = null
        return fun(phrase: String) {
            // SYNCHRONOUS late-callback guard: if the field no longer holds what
            // we last dictated, the user has moved on — they sent (input
            // cleared), tapped the X, or edited by hand — so a trailing final/
            // partial must NOT re-inject text. The LaunchedEffect(value.text)
            // cancel below is async and loses the race with a recognizer's
            // onResults (which fires precisely on stopListening()); this check
            // reads the live field value and closes that gap. Without it, a
            // final arriving just after Send re-populated the box, so the
            // message looked unsent and had to be sent again.
            if (lastEmitted != null && valueState.value.text != lastEmitted) return
            val head = appendDictated(before, phrase)
            val next = head + after
            lastEmitted = next
            onValueChangeState.value(TextFieldValue(next, TextRange(head.length)))
        }
    }
    var listening by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var whisperDictation by remember { mutableStateOf<WhisperDictation?>(null) }
    var serverDictation by remember { mutableStateOf<ServerDictation?>(null) }
    var sonioxDictation by remember { mutableStateOf<SonioxDictation?>(null) }
    val sessionId = remember { AtomicInteger(0) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
            recognizer = null
            whisperDictation?.stop()
            whisperDictation = null
            serverDictation?.stop()
            serverDictation = null
            sonioxDictation?.stop()
            sonioxDictation = null
        }
    }

    // If the field changes to something we didn't emit while a dictation is
    // running — the user sent (input cleared), tapped the X, or edited by
    // hand — cancel the dictation so a trailing streaming partial can't
    // re-inject the old text.
    LaunchedEffect(value.text) {
        if (listening && lastEmitted != null && value.text != lastEmitted) {
            serverDictation?.stop(); serverDictation = null
            whisperDictation?.stop(); whisperDictation = null
            sonioxDictation?.stop(); sonioxDictation = null
            runCatching { recognizer?.stopListening() }
            sessionId.incrementAndGet()
            listening = false
            lastEmitted = null
        }
    }

    fun startServer() {
        val cfg = sttServerConfig(context)
        if (cfg.url.isBlank()) {
            Toast.makeText(
                context,
                "Není nastavená adresa STT serveru. Otevřete Nastavení → Voice.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val emit = dictationEmitter()
        val dictation = ServerDictation(
            context = context.applicationContext,
            baseUrl = cfg.url,
            model = cfg.model,
            apiKey = cfg.apiKey,
            continuous = false,
            onFinal = { phrase ->
                emit(phrase)
                serverDictation?.stop()
                serverDictation = null
                listening = false
            },
            onError = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                serverDictation?.stop()
                serverDictation = null
                listening = false
            },
        )
        serverDictation = dictation
        dictation.start()
        listening = true
    }

    fun startSoniox() {
        val cfg = sonioxConfig(context)
        if (cfg.apiKey.isBlank()) {
            Toast.makeText(
                context,
                "Chybí Soniox API klíč. Otevřete Nastavení → Voice.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val emit = dictationEmitter()
        // Guard against late partials/finals landing after this dictation is
        // done (e.g. a trailing message arriving after the user already sent
        // the input) re-injecting text into the field. onFinal bumps the
        // session so nothing after it can write.
        val mySession = sessionId.incrementAndGet()
        // Silence tolerance read straight from prefs (same style as the rest of
        // this file — no AppSettings dependency here), clamped to 1–10 s.
        val silenceMs = context
            .getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
            .getInt("dictation_silence_ms", 4000).coerceIn(1000, 10000)
        val dictation = SonioxDictation(
            context = context.applicationContext,
            apiKey = cfg.apiKey,
            continuous = false,
            silenceMs = silenceMs,
            // Live word-by-word growth: each partial re-splices the running
            // transcript in at the cursor position.
            onPartial = { phrase ->
                if (sessionId.get() != mySession) return@SonioxDictation
                emit(phrase)
            },
            onFinal = { phrase ->
                if (sessionId.get() != mySession) return@SonioxDictation
                sessionId.incrementAndGet() // invalidate any trailing callback
                emit(phrase)
                sonioxDictation?.stop()
                sonioxDictation = null
                listening = false
            },
            onError = { msg ->
                if (sessionId.get() != mySession) return@SonioxDictation
                sessionId.incrementAndGet()
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                sonioxDictation?.stop()
                sonioxDictation = null
                listening = false
            },
        )
        sonioxDictation = dictation
        dictation.start()
        listening = true
    }

    // The system voice dialog (RecognizerIntent activity). This is the
    // reliable way to reach Google's Czech recognition on devices where the
    // bound SpeechRecognizer service reports the language unsupported but
    // Gboard voice typing works — it routes through the same Google voice
    // backend. One-shot, shows a system overlay, returns recognised text.
    val googleDialogLauncher = rememberLauncherForActivityResult(
        StartActivityForResult()
    ) { result ->
        listening = false
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (text.isNotBlank()) {
            dictationEmitter()(text)
        }
    }

    fun launchGoogleDialog(): Boolean {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Mluvte česky…")
        }
        return runCatching { googleDialogLauncher.launch(intent); true }.getOrDefault(false)
    }

    fun startWhisper() {
        if (!WhisperModelManager.isModelReady(context)) {
            Toast.makeText(
                context,
                "Whisper model není stažený. Otevřete Nastavení → Voice a stáhněte ho.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val emit = dictationEmitter()
        val dictation = WhisperDictation(
            context = context.applicationContext,
            continuous = false,
            onFinal = { phrase ->
                emit(phrase)
                whisperDictation?.stop()
                whisperDictation = null
                listening = false
            },
            onError = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                whisperDictation?.stop()
                whisperDictation = null
                listening = false
            },
        )
        whisperDictation = dictation
        dictation.start()
        listening = true
    }

    fun startSr() {
        val rec = recognizer ?: createCzechRecognizerSmart(context).also { recognizer = it }
        if (rec == null) {
            // No bound recognizer — try the Google voice dialog.
            if (launchGoogleDialog()) {
                listening = true
                return
            }
            Toast.makeText(
                context,
                "Rozpoznávání řeči není dostupné. " +
                    "Nainstalujte Google (Speech Services by Google) " +
                    "nebo přepněte na engine Server v Nastavení → Voice.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val mySession = sessionId.incrementAndGet()
        val emit = dictationEmitter()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            private fun stale() = sessionId.get() != mySession
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (stale()) return
                // Czech unsupported on this device — switch to Vosk for the
                // rest of the composable's lifetime and try again in the
                // same session so the user doesn't need to retap.
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    runCatching { rec.cancel() }
                    runCatching { rec.destroy() }
                    recognizer = null
                    // Bound service has no Czech — try the Google voice
                    // dialog (works wherever Gboard voice does).
                    if (launchGoogleDialog()) return
                    listening = false
                    Toast.makeText(
                        context,
                        "Tento přístroj nepodporuje českou Google STT. " +
                            "Přepněte na engine Server v Nastavení → Voice.",
                        Toast.LENGTH_LONG,
                    ).show()
                    return
                }
                listening = false
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Toast.makeText(
                        context,
                        recognizerErrorLabel(error),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            override fun onResults(results: Bundle?) {
                if (stale()) return
                val phrase = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (phrase.isNotBlank()) {
                    emit(phrase)
                }
                listening = false
            }
            override fun onPartialResults(partial: Bundle?) {
                if (stale()) return
                val phrase = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (phrase.isNotBlank()) {
                    emit(phrase)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
        listening = true
    }

    fun startListening() {
        when (engine) {
            SttEngine.SERVER -> startServer()
            SttEngine.WHISPER -> startWhisper()
            SttEngine.SYSTEM -> startSr()
            SttEngine.SONIOX -> startSoniox()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startListening()
        pendingStart = false
    }

    // Report the mic state upward so the caller can pause competing listeners
    // (the wake word grabs the same mic) while a dictation is running.
    val onListeningChangeState = rememberUpdatedState(onListeningChange)
    LaunchedEffect(listening) { onListeningChangeState.value(listening) }

    // Hands-free start: when the wake word bumps the signal, begin dictation
    // exactly like a tap — request the mic permission if we don't have it yet.
    // Guarded so a wake arriving mid-dictation can't restart on top of itself.
    LaunchedEffect(autoStartSignal) {
        if (autoStartSignal <= 0 || listening || pendingStart) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startListening()
        } else {
            pendingStart = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    IconButton(
        onClick = {
            if (pendingStart) return@IconButton
            if (listening) {
                serverDictation?.stop()
                serverDictation = null
                whisperDictation?.stop()
                whisperDictation = null
                sonioxDictation?.stop()
                sonioxDictation = null
                recognizer?.stopListening()
                listening = false
                return@IconButton
            }
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startListening()
            } else {
                pendingStart = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
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
    val context = LocalContext.current
    var speaking by remember { mutableStateOf(false) }
    val textState = rememberUpdatedState(text)

    IconButton(
        onClick = {
            if (speaking) {
                stopAllTts()
                speaking = false
            } else {
                val payload = textState.value
                if (payload.isBlank()) return@IconButton
                speaking = true
                speakRouted(
                    context, payload,
                    onFinish = { speaking = false },
                    onError = { msg ->
                        // MUST reset speaking too — otherwise a failed read left
                        // the button stuck "speaking", so the NEXT tap hit the
                        // stop branch (stopAllTts) instead of playing, and
                        // read-aloud silently did nothing until the button was
                        // tapped an even number of times. (Errors were common on
                        // the old TTS build, which is why it wedged.)
                        speaking = false
                        Toast.makeText(context, "TTS: $msg", Toast.LENGTH_LONG).show()
                    },
                )
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
