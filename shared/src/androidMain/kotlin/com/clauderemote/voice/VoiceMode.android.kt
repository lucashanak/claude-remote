package com.clauderemote.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

private enum class VoiceState { Listening, Thinking, Speaking, NoPermission, Error }

@Composable
actual fun VoiceModeScreen(
    onSend: (String) -> Unit,
    latestAssistantId: String?,
    latestAssistantText: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        VoiceModeFrame(state = VoiceState.Error, partial = "", onClose = onClose)
        return
    }

    val onSendState = rememberUpdatedState(onSend)
    val initialAssistantId = remember(latestAssistantId) { latestAssistantId } // snapshot at first composition
    val initialIdRef = remember { mutableStateOf(initialAssistantId) }

    var state by remember { mutableStateOf(VoiceState.Listening) }
    var partial by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (!granted) state = VoiceState.NoPermission
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val controller = remember {
        DialogueController(
            context = context.applicationContext,
            onPartial = { partial = it },
            onCommit = { text ->
                partial = ""
                state = VoiceState.Thinking
                onSendState.value(text)
            },
            onStateChange = { state = it },
        )
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) controller.start()
        onDispose { controller.stop() }
    }

    // Speak each fresh assistant message exactly once.
    LaunchedEffect(latestAssistantId) {
        val initial = initialIdRef.value
        val id = latestAssistantId
        val body = latestAssistantText
        if (id != null && id != initial && !body.isNullOrBlank()) {
            controller.speak(body)
        }
    }

    VoiceModeFrame(state = state, partial = partial, onClose = onClose)
}

@Composable
private fun VoiceModeFrame(
    state: VoiceState,
    partial: String,
    onClose: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF050A12),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(onClose)
            Orb(state)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stateLabel(state),
                    color = Color(0xFFBFD0F0),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = partial.ifBlank { hintFor(state) },
                    color = if (partial.isBlank()) Color(0xFF6F7E96) else Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Row(onClose: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close voice mode",
                tint = Color(0xFFBFD0F0),
            )
        }
    }
}

@Composable
private fun Orb(state: VoiceState) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val (color, durationMs, minScale, maxScale) = when (state) {
        VoiceState.Listening -> OrbAnim(Color(0xFF4E9CFF), 1100, 0.92f, 1.08f)
        VoiceState.Thinking  -> OrbAnim(Color(0xFFFFB44E), 1700, 0.96f, 1.04f)
        VoiceState.Speaking  -> OrbAnim(Color(0xFF4EE0A0), 1300, 0.94f, 1.10f)
        VoiceState.NoPermission, VoiceState.Error ->
            OrbAnim(Color(0xFFFF5C5C), 2000, 1f, 1f)
    }
    val scale by infinite.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "orbScale",
    )
    Box(
        modifier = Modifier
            .size(220.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

private data class OrbAnim(val color: Color, val duration: Int, val min: Float, val max: Float)

private fun stateLabel(state: VoiceState): String = when (state) {
    VoiceState.Listening    -> "Poslouchám…"
    VoiceState.Thinking     -> "Přemýšlím…"
    VoiceState.Speaking     -> "Mluvím…"
    VoiceState.NoPermission -> "Chybí oprávnění mikrofonu"
    VoiceState.Error        -> "Rozpoznávání není dostupné"
}

private fun hintFor(state: VoiceState): String = when (state) {
    VoiceState.Listening    -> "Mluvte teď — česky"
    VoiceState.Thinking     -> ""
    VoiceState.Speaking     -> ""
    VoiceState.NoPermission -> "Otevřete nastavení a povolte mikrofon."
    VoiceState.Error        -> "Toto zařízení nepodporuje rozpoznávání řeči."
}

// ─────────────────────────────────────────────────────────────────────────────
// Controller — owns the recognizer + TTS loop while voice mode is on screen.
// ─────────────────────────────────────────────────────────────────────────────

private class DialogueController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onCommit: (String) -> Unit,
    private val onStateChange: (VoiceState) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val sessionGen = AtomicInteger(0)
    @Volatile private var stopped = false
    @Volatile private var paused = false  // true while TTS is speaking

    fun start() {
        stopped = false
        paused = false
        ensureRecognizer()
        beginListening()
    }

    fun stop() {
        stopped = true
        sessionGen.incrementAndGet()
        TtsHolder.stop()
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }

    fun speak(text: String) {
        if (stopped) return
        paused = true
        sessionGen.incrementAndGet() // discard any in-flight recognition results
        recognizer?.let { runCatching { it.cancel() } }
        onStateChange(VoiceState.Speaking)
        TtsHolder.speak(context, text) {
            // Completion is already dispatched on the main thread by TtsHolder.
            paused = false
            if (!stopped) {
                onStateChange(VoiceState.Listening)
                beginListening()
            }
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = createCzechRecognizerForVoice(context)
    }

    private fun beginListening() {
        if (stopped || paused) return
        val rec = recognizer ?: return
        val mySession = sessionGen.incrementAndGet()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        rec.setRecognitionListener(object : RecognitionListener {
            private fun stale() = stopped || paused || sessionGen.get() != mySession
            override fun onReadyForSpeech(params: Bundle?) {
                if (!stale()) onStateChange(VoiceState.Listening)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (stale()) return
                onPartial("")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        // Common silence/busy cases — just restart.
                        scheduleRestart()
                    }
                    SpeechRecognizer.ERROR_CLIENT -> scheduleRestart()
                    else -> onStateChange(VoiceState.Error)
                }
            }
            override fun onResults(results: Bundle?) {
                if (stale()) return
                val phrase = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (phrase.isNotBlank()) {
                    onCommit(phrase) // caller transitions to Thinking
                } else {
                    scheduleRestart()
                }
            }
            override fun onPartialResults(partial: Bundle?) {
                if (stale()) return
                val phrase = partial
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (phrase.isNotBlank()) onPartial(phrase)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { rec.startListening(intent) }
    }

    private fun scheduleRestart() {
        mainHandler.postDelayed({
            if (!stopped && !paused) beginListening()
        }, 250L)
    }
}

private fun createCzechRecognizerForVoice(context: Context): SpeechRecognizer {
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
