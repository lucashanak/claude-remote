package com.clauderemote.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

private const val CZECH_LOCALE_TAG = "cs-CZ"

// ── TTS ────────────────────────────────────────────────────────────────────
//
// One process-wide TextToSpeech engine. Tapping a different SpeakerButton
// interrupts whatever is currently being read.

private object TtsHolder {
    private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    private val currentUtterance = AtomicReference<String?>(null)
    private val currentCompletion = AtomicReference<(() -> Unit)?>(null)

    fun speak(context: Context, text: String, onFinish: () -> Unit) {
        // Replace any in-flight completion before starting a new utterance so
        // that the previous SpeakerButton's "speaking" state is reset.
        currentCompletion.getAndSet(onFinish)?.invoke()

        val existing = engine
        if (existing != null && ready) {
            enqueue(existing, text)
            return
        }
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                currentCompletion.getAndSet(null)?.invoke()
                return@TextToSpeech
            }
            val t = engine ?: return@TextToSpeech
            t.language = Locale.forLanguageTag(CZECH_LOCALE_TAG)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Required override on older API levels.")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)

                private fun finish(utteranceId: String?) {
                    if (utteranceId != null && utteranceId == currentUtterance.get()) {
                        currentUtterance.set(null)
                        currentCompletion.getAndSet(null)?.invoke()
                    }
                }
            })
            ready = true
            enqueue(t, text)
        }
    }

    private fun enqueue(t: TextToSpeech, text: String) {
        val id = "cr-utt-${System.nanoTime()}"
        currentUtterance.set(id)
        t.stop()
        t.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
    }

    fun stop() {
        engine?.stop()
        currentUtterance.set(null)
        currentCompletion.getAndSet(null)?.invoke()
    }
}

// ── STT ────────────────────────────────────────────────────────────────────

private fun createCzechRecognizer(context: Context): SpeechRecognizer {
    // Samsung devices ship their own recognizer backend which is unreliable
    // for cs-CZ. Force the Google recognition service when present.
    val googleComponent = ComponentName(
        "com.google.android.googlequicksearchbox",
        "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
    )
    return try {
        SpeechRecognizer.createSpeechRecognizer(context, googleComponent)
    } catch (_: Throwable) {
        SpeechRecognizer.createSpeechRecognizer(context)
    }
}

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
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return

    val onTextChangeState = rememberUpdatedState(onTextChange)
    val currentTextState = rememberUpdatedState(currentText)
    var listening by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var baseText by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.destroy()
            recognizer = null
        }
    }

    fun startListening() {
        val rec = recognizer ?: createCzechRecognizer(context).also { recognizer = it }
        baseText = currentTextState.value
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                listening = false
            }
            override fun onResults(results: Bundle?) {
                val phrase = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (phrase.isNotBlank()) {
                    onTextChangeState.value(appendDictated(baseText, phrase))
                }
                listening = false
            }
            override fun onPartialResults(partial: Bundle?) {
                val phrase = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (phrase.isNotBlank()) {
                    onTextChangeState.value(appendDictated(baseText, phrase))
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
        listening = true
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStart) startListening()
        pendingStart = false
    }

    IconButton(
        onClick = {
            if (listening) {
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
                TtsHolder.stop()
                speaking = false
            } else {
                val payload = textState.value
                if (payload.isBlank()) return@IconButton
                speaking = true
                TtsHolder.speak(context, payload) {
                    speaking = false
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

@Suppress("unused")
private val supportsModernTts = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
