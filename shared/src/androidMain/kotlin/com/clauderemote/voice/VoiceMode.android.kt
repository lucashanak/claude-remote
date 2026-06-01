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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private sealed class VoiceState {
    object Listening : VoiceState()
    object Thinking : VoiceState()
    object Speaking : VoiceState()
    object NoPermission : VoiceState()
    /** [reason] is shown to the user verbatim — already localised Czech. */
    data class Error(val reason: String) : VoiceState()
}

@Composable
actual fun VoiceModeScreen(
    onSend: (String) -> Unit,
    latestAssistantId: String?,
    latestAssistantText: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        VoiceModeFrame(
            state = VoiceState.Error(
                "Toto zařízení nemá službu rozpoznávání řeči. " +
                    "Nainstalujte aplikaci Google z Play Store nebo " +
                    "Mluva (Speech Services by Google) a zkuste znovu."
            ),
            partial = "",
            onClose = onClose,
        )
        return
    }

    val onSendState = rememberUpdatedState(onSend)
    // Capture the assistant id present when voice mode opens; we only auto-
    // speak messages newer than this. Plain `remember { latestAssistantId }`
    // captures once on first composition and never changes thereafter.
    val initialAssistantId = remember { latestAssistantId }

    // Explicit type — without it, the release compiler narrows to
    // `VoiceState.Listening` and later assignments of other subtypes
    // (Thinking, Speaking, Error, …) fail with a type mismatch.
    var state by remember { mutableStateOf<VoiceState>(VoiceState.Listening) }
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

    // SYSTEM (Google) engine fallback: a launcher for the system voice dialog
    // activity, driven one turn at a time by the controller on devices whose
    // bound recognizer can't do Czech. controllerRef breaks the cycle (the
    // launcher callback needs the controller; the controller needs the
    // launcher).
    val controllerRef = remember { mutableStateOf<DialogueController?>(null) }
    val googleVoiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        controllerRef.value?.submitExternalResult(text)
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
            requestExternalListen = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Mluvte česky…")
                }
                runCatching { googleVoiceLauncher.launch(intent) }
            },
        ).also { controllerRef.value = it }
    }

    DisposableEffect(hasPermission) {
        if (hasPermission) controller.start()
        onDispose { controller.stop() }
    }

    // Speak each fresh assistant message exactly once. Also toast a tiny
    // diagnostic so the user can confirm whether the reply ever reaches the
    // app — if Toast fires but Thinking persists, the trigger is wrong; if
    // no Toast, the transcript stream / claude session is the culprit.
    LaunchedEffect(latestAssistantId) {
        val id = latestAssistantId ?: return@LaunchedEffect
        if (id == initialAssistantId) return@LaunchedEffect
        val body = latestAssistantText
        android.widget.Toast.makeText(
            context,
            "Nová odpověď (id …${id.takeLast(6)}, ${body?.length ?: 0} znaků)",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
        if (!body.isNullOrBlank()) controller.speak(body)
    }

    // Watchdog: if the assistant never replies (terminal didn't echo, claude
    // is busy, transcript flow stalled, …) we'd otherwise sit in "Přemýšlím"
    // forever. After 30 s of no reply, fall back to Listening so the user
    // can try again without having to exit and re-enter voice mode.
    LaunchedEffect(state) {
        if (state is VoiceState.Thinking) {
            kotlinx.coroutines.delay(30_000)
            if (state is VoiceState.Thinking) {
                state = VoiceState.Listening
                controller.resume()
            }
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
    // Bottom panel only — the chat stays visible above it, so the user sees
    // the conversation (with the assistant reply being read aloud) and their
    // own live transcript at the same time, instead of an opaque orb screen.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF0B1422),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Orb(state, sizeDp = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stateLabel(state),
                        color = Color(0xFFBFD0F0),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = partial.ifBlank { hintFor(state) },
                        color = if (partial.isBlank()) Color(0xFF6F7E96) else Color.White,
                        fontSize = 15.sp,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close voice mode",
                        tint = Color(0xFFBFD0F0),
                    )
                }
            }
        }
    }
}

@Composable
private fun Orb(state: VoiceState, sizeDp: androidx.compose.ui.unit.Dp = 220.dp) {
    val infinite = rememberInfiniteTransition(label = "orb")
    val (color, durationMs, minScale, maxScale) = when (state) {
        is VoiceState.Listening    -> OrbAnim(Color(0xFF4E9CFF), 1100, 0.92f, 1.08f)
        is VoiceState.Thinking     -> OrbAnim(Color(0xFFFFB44E), 1700, 0.96f, 1.04f)
        is VoiceState.Speaking     -> OrbAnim(Color(0xFF4EE0A0), 1300, 0.94f, 1.10f)
        is VoiceState.NoPermission,
        is VoiceState.Error        -> OrbAnim(Color(0xFFFF5C5C), 2000, 1f, 1f)
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
            .size(sizeDp)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp * 0.64f)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(sizeDp * 0.3f),
            )
        }
    }
}

private data class OrbAnim(val color: Color, val duration: Int, val min: Float, val max: Float)

private fun stateLabel(state: VoiceState): String = when (state) {
    is VoiceState.Listening    -> "Poslouchám…"
    is VoiceState.Thinking     -> "Přemýšlím…"
    is VoiceState.Speaking     -> "Mluvím…"
    is VoiceState.NoPermission -> "Chybí oprávnění mikrofonu"
    is VoiceState.Error        -> "Rozpoznávání selhalo"
}

private fun hintFor(state: VoiceState): String = when (state) {
    is VoiceState.Listening    -> "Mluvte teď — česky"
    is VoiceState.Thinking     -> ""
    is VoiceState.Speaking     -> ""
    is VoiceState.NoPermission -> "Otevřete nastavení a povolte mikrofon."
    is VoiceState.Error        -> state.reason
}

// ─────────────────────────────────────────────────────────────────────────────
// Controller — owns the recognizer + TTS loop while voice mode is on screen.
// ─────────────────────────────────────────────────────────────────────────────

private class DialogueController(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onCommit: (String) -> Unit,
    private val onStateChange: (VoiceState) -> Unit,
    // For the SYSTEM (Google) engine on devices whose bound recognizer can't
    // do Czech (e.g. HyperOS): invoked to launch the system Google voice
    // dialog activity for one turn. Results come back via submitExternalResult.
    private val requestExternalListen: (() -> Unit)? = null,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var whisperDictation: WhisperDictation? = null
    private var serverDictation: ServerDictation? = null
    private val engine = selectedSttEngine(context)
    private val useWhisper = engine == com.clauderemote.model.SttEngine.WHISPER
    private val useServer = engine == com.clauderemote.model.SttEngine.SERVER
    private val sessionGen = AtomicInteger(0)
    @Volatile private var stopped = false
    @Volatile private var paused = false  // true while TTS is speaking
    // Flips on once the bound recognizer reports no Czech: route SYSTEM
    // listening through the Google voice dialog activity for the rest of the
    // session instead of erroring out.
    @Volatile private var forceExternal = false

    fun start() {
        stopped = false
        paused = false
        if (!useWhisper && !useServer) {
            // SYSTEM engine: lazily build the bound SpeechRecognizer.
            ensureRecognizer()
        }
        beginListening()
    }

    fun stop() {
        stopped = true
        sessionGen.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
        stopAllTts()
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
        whisperDictation?.stop()
        whisperDictation = null
        serverDictation?.stop()
        serverDictation = null
    }

    /** Restart listening on the configured engine without re-initialising. */
    fun resume() {
        if (stopped) return
        paused = false
        beginListening()
    }

    /**
     * Result from the Google voice dialog activity (SYSTEM engine fallback).
     * Commits a non-empty phrase; on empty/cancel, re-listens after a beat so
     * a dismissed dialog doesn't reopen in a tight loop.
     */
    fun submitExternalResult(text: String) {
        if (stopped || paused) return
        val t = text.trim()
        if (t.isNotBlank()) {
            onCommit(t)
        } else {
            mainHandler.postDelayed({ if (!stopped && !paused) beginListening() }, 700)
        }
    }

    fun speak(text: String) {
        if (stopped) return
        paused = true
        sessionGen.incrementAndGet() // discard any in-flight recognition results
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.let { runCatching { it.cancel() } }
        whisperDictation?.stop()
        whisperDictation = null
        serverDictation?.stop()
        serverDictation = null
        onStateChange(VoiceState.Speaking)
        val onSpoken = {
            paused = false
            if (!stopped) {
                onStateChange(VoiceState.Listening)
                beginListening()
            } else Unit
        }
        speakRouted(
            context, text,
            onFinish = onSpoken,
            onError = { msg -> onStateChange(VoiceState.Error("TTS: $msg")) },
        )
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = createCzechRecognizerSmart(context)
        if (recognizer == null) {
            onStateChange(
                VoiceState.Error(
                    "Nepodařilo se inicializovat rozpoznávač. Přepněte na engine " +
                        "Server v Nastavení → Voice."
                )
            )
        }
    }

    private fun beginListening() {
        if (stopped || paused) return
        if (useServer) {
            beginServerListening()
            return
        }
        if (useWhisper) {
            beginWhisperListening()
            return
        }
        // SYSTEM (Google). If the device's bound recognizer already failed on
        // Czech, drive listening through the Google voice dialog activity.
        if (forceExternal && requestExternalListen != null) {
            onStateChange(VoiceState.Listening)
            // Small delay so any prior recognizer/mic is released first.
            mainHandler.postDelayed({
                if (!stopped && !paused) requestExternalListen.invoke()
            }, 150)
            return
        }
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
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                        // Bound recognizer doesn't know Czech. If we can launch
                        // the system Google voice dialog, switch to that for the
                        // rest of the session; otherwise surface an error.
                        runCatching { rec.cancel() }
                        runCatching { rec.destroy() }
                        recognizer = null
                        if (requestExternalListen != null) {
                            forceExternal = true
                            beginListening()
                        } else {
                            onStateChange(
                                VoiceState.Error(
                                    "Tento přístroj nepodporuje českou Google STT. " +
                                        "Přepněte na engine Server v Nastavení → Voice."
                                )
                            )
                        }
                    }
                    else -> onStateChange(VoiceState.Error(recognizerErrorLabel(error)))
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

    private fun beginServerListening() {
        if (serverDictation != null) return
        val cfg = sttServerConfig(context)
        if (cfg.url.isBlank()) {
            onStateChange(VoiceState.Error("Není nastavená adresa STT serveru. Otevřete Nastavení → Voice."))
            return
        }
        val mySession = sessionGen.incrementAndGet()
        val dictation = ServerDictation(
            context = context,
            baseUrl = cfg.url,
            model = cfg.model,
            apiKey = cfg.apiKey,
            continuous = true,
            onFinal = { phrase ->
                if (stopped || paused || sessionGen.get() != mySession) return@ServerDictation
                serverDictation?.stop()
                serverDictation = null
                if (phrase.isNotBlank()) onCommit(phrase) else beginListening()
            },
            onError = { msg ->
                if (sessionGen.get() != mySession) return@ServerDictation
                serverDictation?.stop()
                serverDictation = null
                onStateChange(VoiceState.Error(msg))
            },
            onListening = {
                if (!stopped && !paused && sessionGen.get() == mySession) {
                    onStateChange(VoiceState.Listening)
                }
            },
            onPartial = { text ->
                if (!stopped && !paused && sessionGen.get() == mySession) onPartial(text)
            },
        )
        serverDictation = dictation
        dictation.start()
    }

    private fun beginWhisperListening() {
        if (whisperDictation != null) return
        if (!WhisperModelManager.isModelReady(context)) {
            onStateChange(
                VoiceState.Error("Whisper model není stažený. Otevřete Nastavení → Voice.")
            )
            return
        }
        val mySession = sessionGen.incrementAndGet()
        val dictation = WhisperDictation(
            context = context,
            continuous = true,
            onFinal = { phrase ->
                if (stopped || paused || sessionGen.get() != mySession) return@WhisperDictation
                whisperDictation?.stop()
                whisperDictation = null
                if (phrase.isNotBlank()) onCommit(phrase) else beginListening()
            },
            onError = { msg ->
                if (sessionGen.get() != mySession) return@WhisperDictation
                whisperDictation?.stop()
                whisperDictation = null
                onStateChange(VoiceState.Error(msg))
            },
        )
        whisperDictation = dictation
        onStateChange(VoiceState.Listening)
        dictation.start()
    }

    private fun scheduleRestart() {
        // Capture the current session token; if anything else (a new result,
        // an interrupt to speak()) has advanced sessionGen by the time this
        // fires, the restart is stale and must be dropped — otherwise we
        // can race the TTS-completion path and call startListening twice in
        // a row on the same recognizer, which sticks it on ERROR_BUSY.
        val tag = sessionGen.get()
        mainHandler.postDelayed({
            if (!stopped && !paused && sessionGen.get() == tag) beginListening()
        }, 250L)
    }
}

// `createCzechRecognizerSmart` lives in Recognizer.android.kt and is the
// single source of truth for recognizer construction.
