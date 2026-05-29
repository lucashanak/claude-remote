package com.clauderemote.voice

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.clauderemote.model.SttEngine
import com.clauderemote.model.TtsEngine
import com.clauderemote.storage.AppSettings
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import kotlinx.coroutines.launch

@Composable
actual fun WakeWordSettingsCard(settings: AppSettings) {
    val context = LocalContext.current
    val c = CRTheme.colors
    val m = CRTheme.metrics
    val scope = rememberCoroutineScope()

    var serverUrl by remember { mutableStateOf(settings.sttServerUrl) }
    var serverModel by remember { mutableStateOf(settings.sttServerModel) }
    var ttsModel by remember { mutableStateOf(settings.ttsServerModel) }
    var ttsVoice by remember { mutableStateOf(settings.ttsServerVoice) }
    var catalog by remember { mutableStateOf<List<ServerCatalog.ModelInfo>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var voicesCatalog by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingVoices by remember { mutableStateOf(false) }

    // Pin both engines to SERVER — the legacy SYSTEM/Vosk/Whisper paths
    // were removed; the app only does STT/TTS through the self-hosted
    // OpenAI-compatible endpoint.
    LaunchedEffect(Unit) {
        if (settings.sttEngine != SttEngine.SERVER) settings.sttEngine = SttEngine.SERVER
        if (settings.ttsEngine != TtsEngine.SERVER) settings.ttsEngine = TtsEngine.SERVER
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
        // ── STT (Server) ─────────────────────────────────────────────
        Text("Rozpoznávání řeči — Server", style = CRType.cardTitle, color = c.text)
        Text(
            "OpenAI-kompatibilní endpoint (např. Open WebUI / Speaches / " +
                "faster-whisper-server).",
            style = CRType.bodyDim, color = c.textDim,
        )
        androidx.compose.material3.OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it; settings.sttServerUrl = it
                catalog = emptyList()
                voicesCatalog = emptyList()
            },
            label = { Text("URL serveru (https://…/api/v1)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.material3.OutlinedTextField(
            value = serverModel,
            onValueChange = { serverModel = it; settings.sttServerModel = it },
            label = { Text("STT model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (serverUrl.isNotBlank()) {
            Button(
                onClick = {
                    loadingCatalog = true
                    scope.launch {
                        try {
                            val result = ServerCatalog.fetchModels(serverUrl, settings.sttServerApiKey)
                            catalog = result
                            if (result.isEmpty()) {
                                Toast.makeText(context, "Server vrátil prázdný seznam modelů.", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Throwable) {
                            Toast.makeText(context, "Modely: ${e.message ?: "neznámá chyba"}", Toast.LENGTH_LONG).show()
                        } finally {
                            loadingCatalog = false
                        }
                    }
                },
                enabled = !loadingCatalog,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
            ) {
                Text(
                    if (loadingCatalog) "Načítám…"
                    else if (catalog.isEmpty()) "Načíst modely ze serveru"
                    else "Obnovit modely (${catalog.size})"
                )
            }
            if (catalog.isNotEmpty()) {
                ModelDropdown(
                    label = "STT model",
                    options = catalog.filter { ServerCatalog.isStt(it) }.map { it.id },
                    selected = serverModel,
                    onSelect = { serverModel = it; settings.sttServerModel = it },
                )
            }
        }

        androidx.compose.material3.HorizontalDivider(color = c.border)

        // ── TTS (Server) ─────────────────────────────────────────────
        Text("Předčítání — Server", style = CRType.cardTitle, color = c.text)
        Text(
            "Předčítání přes stejný server (např. Piper voice via /v1/audio/speech).",
            style = CRType.bodyDim, color = c.textDim,
        )
        if (catalog.isNotEmpty()) {
            ModelDropdown(
                label = "TTS model",
                options = catalog.filter { ServerCatalog.isTts(it) }.map { it.id },
                selected = ttsModel,
                onSelect = { ttsModel = it; settings.ttsServerModel = it },
            )
        } else {
            androidx.compose.material3.OutlinedTextField(
                value = ttsModel,
                onValueChange = { ttsModel = it; settings.ttsServerModel = it },
                label = { Text("TTS model (např. tts-1)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (voicesCatalog.isNotEmpty()) {
            ModelDropdown(
                label = "Hlas (voice)",
                options = voicesCatalog,
                selected = ttsVoice,
                onSelect = { ttsVoice = it; settings.ttsServerVoice = it },
            )
        } else {
            androidx.compose.material3.OutlinedTextField(
                value = ttsVoice,
                onValueChange = { ttsVoice = it; settings.ttsServerVoice = it },
                label = { Text("Hlas (voice)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (serverUrl.isNotBlank()) {
            Button(
                onClick = {
                    loadingVoices = true
                    scope.launch {
                        try {
                            val result = ServerCatalog.fetchVoices(serverUrl, settings.sttServerApiKey)
                            voicesCatalog = result
                            if (result.isEmpty()) {
                                Toast.makeText(context, "Server vrátil prázdný seznam hlasů.", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Throwable) {
                            Toast.makeText(context, "Hlasy: ${e.message ?: "neznámá chyba"}", Toast.LENGTH_LONG).show()
                        } finally {
                            loadingVoices = false
                        }
                    }
                },
                enabled = !loadingVoices,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
            ) {
                Text(
                    if (loadingVoices) "Načítám hlasy…"
                    else if (voicesCatalog.isEmpty()) "Načíst hlasy ze serveru"
                    else "Obnovit seznam hlasů (${voicesCatalog.size})"
                )
            }
        }
    }
}

@Composable
private fun ModelDropdown(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val c = CRTheme.colors
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = CRType.bodyDim, color = c.textDim)
        Box {
            androidx.compose.material3.OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    selected.ifBlank { "— vyber model —" },
                    maxLines = 1,
                    color = c.text,
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (options.isEmpty()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Žádné modely") },
                        onClick = { expanded = false },
                    )
                }
                options.forEach { opt ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { onSelect(opt); expanded = false },
                    )
                }
            }
        }
    }
}
