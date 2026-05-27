package com.clauderemote.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.clauderemote.model.SttEngine
import com.clauderemote.storage.AppSettings
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.launch

@Composable
actual fun WakeWordHost(
    enabled: Boolean,
    voiceModeActive: Boolean,
    onWake: () -> Unit,
) {
    val context = LocalContext.current
    val onWakeState = rememberUpdatedState(onWake)
    val pending by WakeEvents.pendingOpen.collectAsState()

    // Service should listen only while wake-word is enabled, the model
    // is on disk, the mic permission is granted, and voice mode is not
    // currently using the microphone itself.
    val shouldListen = enabled &&
        !voiceModeActive &&
        WakeWordController.isModelReady(context) &&
        WakeWordController.hasMicPermission(context)

    DisposableEffect(shouldListen) {
        if (shouldListen) WakeWordController.startListening(context)
        else WakeWordController.stopListening(context)
        onDispose { WakeWordController.stopListening(context) }
    }

    LaunchedEffect(pending) {
        if (pending) {
            onWakeState.value()
            WakeEvents.acknowledge()
        }
    }
}

@Composable
actual fun WakeWordSettingsCard(settings: AppSettings) {
    val context = LocalContext.current
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf(settings.sttEngine) }
    var serverUrl by remember { mutableStateOf(settings.sttServerUrl) }
    var serverModel by remember { mutableStateOf(settings.sttServerModel) }
    var whisperReady by remember { mutableStateOf(WhisperModelManager.isModelReady(context)) }
    var whisperProgress by remember { mutableStateOf(0f) }
    var whisperDownloading by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(settings.wakeWordEnabled) }
    var modelReady by remember { mutableStateOf(WakeWordController.isModelReady(context)) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val status by WakeWordController.status.collectAsState()
    val progress by WakeWordController.downloadProgress.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (!granted) {
            // User refused the permission prompt — clear the toggle so the
            // UI doesn't show an enabled-but-broken state on the next
            // composition. Re-enabling triggers a fresh permission ask.
            enabled = false
            settings.wakeWordEnabled = false
        }
    }

    fun applyEnabled(newValue: Boolean) {
        enabled = newValue
        settings.wakeWordEnabled = newValue
        if (newValue && !micGranted) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (newValue && !modelReady && status != WakeWordController.Status.Downloading) {
            scope.launch {
                val ok = WakeWordController.downloadModel(context)
                modelReady = ok
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(c.surface, RoundedCornerShape(m.cardRadius))
            .border(1.dp, c.border, RoundedCornerShape(m.cardRadius))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Engine picker ───────────────────────────────────────────────
        Text("Rozpoznávání řeči (STT)", style = CRType.cardTitle, color = c.text)
        val engines = SttEngine.entries
        com.clauderemote.ui.components.Segmented(
            options = engines.indices.toList(),
            selected = engines.indexOf(engine),
            onSelect = { idx ->
                engine = engines[idx]
                settings.sttEngine = engine
            },
            label = { engines[it].displayName },
        )
        when (engine) {
            SttEngine.SYSTEM -> Text(
                "Systémové rozpoznávání (Google). Nejlepší kvalita; vyžaduje podporu " +
                    "češtiny v zařízení. Když chybí, přepne se na Vosk.",
                style = CRType.bodyDim, color = c.textDim,
            )
            SttEngine.VOSK -> {
                Text(
                    "Offline, rychlé, nižší kvalita (~50 MB model).",
                    style = CRType.bodyDim, color = c.textDim,
                )
                if (!modelReady && status != WakeWordController.Status.Downloading) {
                    Button(
                        onClick = {
                            scope.launch { modelReady = WakeWordController.downloadModel(context) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent, contentColor = c.accentInk,
                        ),
                    ) { Text("Stáhnout český Vosk model") }
                }
            }
            SttEngine.WHISPER -> {
                Text(
                    "Offline, výrazně lepší kvalita, ~375 MB. Pomalejší (text se " +
                        "objeví po dořeknutí věty).",
                    style = CRType.bodyDim, color = c.textDim,
                )
                when {
                    whisperDownloading -> {
                        Text("Stahuji Whisper: ${(whisperProgress * 100).toInt()} %",
                            style = CRType.bodyDim, color = c.textDim)
                        LinearProgressIndicator(
                            progress = { whisperProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = c.accent, trackColor = c.surface2,
                        )
                    }
                    !whisperReady -> Button(
                        onClick = {
                            whisperDownloading = true
                            scope.launch {
                                val ok = WhisperModelManager.download(context) { whisperProgress = it }
                                whisperReady = ok
                                whisperDownloading = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent, contentColor = c.accentInk,
                        ),
                    ) { Text("Stáhnout Whisper model (~375 MB)") }
                    else -> Text("Whisper model připraven.", style = CRType.bodyDim, color = c.textDim)
                }
            }
            SttEngine.SERVER -> {
                Text(
                    "Vlastní faster-whisper / Speaches server (OpenAI API). Nejlepší " +
                        "kvalita i rychlost; vyžaduje běžící server dostupný z telefonu.",
                    style = CRType.bodyDim, color = c.textDim,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; settings.sttServerUrl = it },
                    label = { Text("URL serveru (http://…:8000)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = serverModel,
                    onValueChange = { serverModel = it; settings.sttServerModel = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        androidx.compose.material3.HorizontalDivider(color = c.border)

        // ── Wake-word ───────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Wake-word \"Hej Claude\"", style = CRType.cardTitle, color = c.text)
                Text(
                    "Hands-free voice mode. Vyžaduje stažení českého modelu (~50 MB).",
                    style = CRType.bodyDim, color = c.textDim,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { applyEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.accentInk,
                    checkedTrackColor = c.accent,
                ),
            )
        }

        when (status) {
            WakeWordController.Status.Downloading -> {
                Text(
                    "Stahuji model: ${(progress * 100).toInt()} %",
                    style = CRType.bodyDim, color = c.textDim,
                )
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.accent,
                    trackColor = c.surface2,
                )
            }
            WakeWordController.Status.Idle -> {
                if (enabled && !modelReady) {
                    Button(
                        onClick = {
                            scope.launch {
                                val ok = WakeWordController.downloadModel(context)
                                modelReady = ok
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = c.accentInk,
                        ),
                    ) { Text("Stáhnout český model") }
                } else if (enabled && !micGranted) {
                    Text(
                        "Chybí oprávnění mikrofonu — zapněte v nastavení systému.",
                        style = CRType.bodyDim, color = c.textDim,
                    )
                }
            }
            WakeWordController.Status.Ready -> {
                if (enabled) {
                    Text("Model připraven. Spouštím poslech…", style = CRType.bodyDim, color = c.textDim)
                }
            }
            WakeWordController.Status.Listening -> {
                Text("Poslouchám \"Hej Claude\".", style = CRType.bodyDim, color = c.textDim)
            }
        }
    }
}
