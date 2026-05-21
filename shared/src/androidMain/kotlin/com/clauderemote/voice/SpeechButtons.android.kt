package com.clauderemote.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
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
    val sessionId = remember { AtomicInteger(0) }

    DisposableEffect(Unit) {
        onDispose {
            recognizer?.let {
                runCatching { it.cancel() }
                runCatching { it.destroy() }
            }
            recognizer = null
        }
    }

    fun startListening() {
        val rec = recognizer ?: createCzechRecognizer(context).also { recognizer = it }
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
                listening = false
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
