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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    currentText: String,
    onTextChange: (String) -> Unit,
    modifier: Modifier,
    tint: Color,
) {
    val context = LocalContext.current
    val srAvailable = SpeechRecognizer.isRecognitionAvailable(context)
    val voskReady = VoskModelManager.isModelReady(context)
    val whisperReady = WhisperModelManager.isModelReady(context)
    val engine = remember { selectedSttEngine(context) }
    // Render nothing only when no backend is usable — otherwise show the
    // button so the user can tap and see a concrete error (model missing,
    // permission denied, etc.) instead of being unable to interact at all.
    // The Server engine only needs a URL, so it always keeps the button.
    if (engine != SttEngine.SERVER && !srAvailable && !voskReady && !whisperReady) return

    val onTextChangeState = rememberUpdatedState(onTextChange)
    val currentTextState = rememberUpdatedState(currentText)
    var listening by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var voskDictation by remember { mutableStateOf<VoskDictation?>(null) }
    var whisperDictation by remember { mutableStateOf<WhisperDictation?>(null) }
    var serverDictation by remember { mutableStateOf<ServerDictation?>(null) }
    // SYSTEM may auto-fall-back to Vosk if the device can't do Czech; we
    // remember that verdict for the rest of the composable's lifetime.
    var useVosk by remember { mutableStateOf(engine == SttEngine.SYSTEM && !srAvailable && voskReady) }
    val sessionId = remember { AtomicInteger(0) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
            recognizer = null
            voskDictation?.stop()
            voskDictation = null
            whisperDictation?.stop()
            whisperDictation = null
            serverDictation?.stop()
            serverDictation = null
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
        val sessionBase = currentTextState.value
        val dictation = ServerDictation(
            context = context.applicationContext,
            baseUrl = cfg.url,
            model = cfg.model,
            apiKey = cfg.apiKey,
            continuous = false,
            onFinal = { phrase ->
                onTextChangeState.value(appendDictated(sessionBase, phrase))
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
            onTextChangeState.value(appendDictated(currentTextState.value, text))
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
        val sessionBase = currentTextState.value
        val dictation = WhisperDictation(
            context = context.applicationContext,
            continuous = false,
            onFinal = { phrase ->
                onTextChangeState.value(appendDictated(sessionBase, phrase))
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

    fun startVosk() {
        if (!VoskModelManager.isModelReady(context)) {
            Toast.makeText(
                context,
                "Český Vosk model není stažený. Otevřete Nastavení → Voice a stáhněte model.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val sessionBase = currentTextState.value
        val dictation = VoskDictation(
            context = context.applicationContext,
            onPartial = { phrase ->
                onTextChangeState.value(appendDictated(sessionBase, phrase))
            },
            onFinal = { phrase ->
                onTextChangeState.value(appendDictated(sessionBase, phrase))
                voskDictation?.stop()
                voskDictation = null
                listening = false
            },
            onError = { msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                voskDictation?.stop()
                voskDictation = null
                listening = false
            },
        )
        voskDictation = dictation
        dictation.start()
        listening = true
    }

    fun startSr() {
        val rec = recognizer ?: createCzechRecognizerSmart(context).also { recognizer = it }
        if (rec == null) {
            // No bound recognizer — try the Google voice dialog, then Vosk.
            if (launchGoogleDialog()) {
                listening = true
                return
            }
            if (voskReady) {
                useVosk = true
                startVosk()
            } else {
                Toast.makeText(
                    context,
                    "Rozpoznávání řeči není dostupné. " +
                        "Nainstalujte Google (Speech Services by Google), nebo stáhněte český Vosk model v Nastavení → Voice.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        val mySession = sessionId.incrementAndGet()
        val sessionBase = currentTextState.value
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
                    // dialog (works wherever Gboard voice does). Only if no
                    // voice activity exists do we fall back to offline Vosk.
                    if (launchGoogleDialog()) return
                    if (VoskModelManager.isModelReady(context)) {
                        useVosk = true
                        startVosk()
                    } else {
                        listening = false
                        Toast.makeText(
                            context,
                            "Tento přístroj nepodporuje českou Google STT. " +
                                "Stáhněte český Vosk model v Nastavení → Voice.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
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
                    onTextChangeState.value(appendDictated(sessionBase, phrase))
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
                    onTextChangeState.value(appendDictated(sessionBase, phrase))
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
            SttEngine.VOSK -> startVosk()
            SttEngine.SYSTEM -> if (useVosk) startVosk() else startSr()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startListening()
        pendingStart = false
    }

    IconButton(
        onClick = {
            if (pendingStart) return@IconButton
            if (listening) {
                serverDictation?.stop()
                serverDictation = null
                whisperDictation?.stop()
                whisperDictation = null
                voskDictation?.stop()
                voskDictation = null
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
                ServerTts.stop()
                TtsHolder.stop()
                speaking = false
            } else {
                val payload = textState.value
                if (payload.isBlank()) return@IconButton
                speaking = true
                if (selectedTtsEngine(context) == com.clauderemote.model.TtsEngine.SERVER) {
                    val cfg = ttsServerConfig(context)
                    if (cfg.url.isBlank()) {
                        Toast.makeText(context, "Není nastavená adresa serveru (Nastavení → Voice).", Toast.LENGTH_LONG).show()
                        speaking = false
                    } else {
                        ServerTts.speak(context, cfg.url, cfg.model, cfg.voice, cfg.apiKey, payload) {
                            speaking = false
                        }
                    }
                } else {
                    TtsHolder.speak(context, payload) {
                        speaking = false
                    }
                }
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
