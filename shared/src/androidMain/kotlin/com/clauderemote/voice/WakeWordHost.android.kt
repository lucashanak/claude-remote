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
    var apiKey by remember { mutableStateOf(settings.sttServerApiKey) }
    var serverModel by remember { mutableStateOf(settings.sttServerModel) }
    var ttsModel by remember { mutableStateOf(settings.ttsServerModel) }
    var ttsVoice by remember { mutableStateOf(settings.ttsServerVoice) }
    var catalog by remember { mutableStateOf<List<ServerCatalog.ModelInfo>>(emptyList()) }
    var loadingCatalog by remember { mutableStateOf(false) }
    var voicesCatalog by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingVoices by remember { mutableStateOf(false) }
    var ttsEngine by remember { mutableStateOf(settings.ttsEngine) }
    var gcloudKey by remember { mutableStateOf(settings.googleCloudApiKey) }
    var gcloudVoice by remember { mutableStateOf(settings.googleCloudVoice) }
    var testing by remember { mutableStateOf(false) }

    // Pin STT to SERVER — the legacy SYSTEM/Vosk/Whisper STT paths were
    // removed; the device recognizer can't do Czech here. TTS, however,
    // now offers a real choice (on-device Google / server / Google Cloud).
    LaunchedEffect(Unit) {
        if (settings.sttEngine != SttEngine.SERVER) settings.sttEngine = SttEngine.SERVER
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
            value = apiKey,
            onValueChange = { apiKey = it; settings.sttServerApiKey = it },
            label = { Text("API klíč (nepovinný)") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            ),
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

        // ── TTS (engine picker) ──────────────────────────────────────
        Text("Předčítání (TTS)", style = CRType.cardTitle, color = c.text)
        ModelDropdown(
            label = "Engine",
            options = TtsEngine.entries.map { it.displayName },
            selected = ttsEngine.displayName,
            onSelect = { name ->
                val picked = TtsEngine.entries.firstOrNull { it.displayName == name } ?: TtsEngine.SERVER
                ttsEngine = picked; settings.ttsEngine = picked
            },
        )

        when (ttsEngine) {
            TtsEngine.SYSTEM -> {
                Text(
                    "Google TTS přímo v zařízení — rychlé, zdarma, offline. " +
                        "Vynutí engine „com.google.android.tts“ (na HyperOS bývá výchozí " +
                        "Xiaomi). Vyžaduje nainstalovaný hlas cs-CZ " +
                        "(Nastavení Androidu → Řeč → Text na řeč).",
                    style = CRType.bodyDim, color = c.textDim,
                )
            }

            TtsEngine.SERVER -> {
                Text(
                    "Předčítání přes stejný server (Piper rychlý CZ, nebo XTTS " +
                        "pomalejší ale bilingvní — voice „xtts:default“).",
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
                        label = { Text("Hlas (voice) — např. cs_CZ-jirka-medium nebo xtts:default") },
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

            TtsEngine.GOOGLE_CLOUD -> {
                Text(
                    "Google Cloud Text-to-Speech — nejlepší kvalita, rychlé. " +
                        "Vyžaduje API klíč (projekt + billing) a text odchází Googlu.",
                    style = CRType.bodyDim, color = c.textDim,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = gcloudKey,
                    onValueChange = { gcloudKey = it; settings.googleCloudApiKey = it },
                    label = { Text("Google Cloud API klíč") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                ModelDropdown(
                    label = "Hlas (přednastavené)",
                    options = listOf("cs-CZ-Wavenet-A", "cs-CZ-Standard-A"),
                    selected = gcloudVoice,
                    onSelect = { gcloudVoice = it; settings.googleCloudVoice = it },
                )
                androidx.compose.material3.OutlinedTextField(
                    value = gcloudVoice,
                    onValueChange = { gcloudVoice = it; settings.googleCloudVoice = it },
                    label = { Text("Hlas (nebo zadej jiný z Google katalogu)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Button(
            onClick = {
                testing = true
                speakRouted(
                    context,
                    "Toto je test hlasu. Otevři pull request a smergni branch do mainu.",
                    onFinish = { testing = false },
                    onError = { msg ->
                        testing = false
                        Toast.makeText(context, "Test: $msg", Toast.LENGTH_LONG).show()
                    },
                )
            },
            enabled = !testing,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
        ) {
            Text(if (testing) "Přehrávám…" else "🔊 Otestovat hlas")
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
