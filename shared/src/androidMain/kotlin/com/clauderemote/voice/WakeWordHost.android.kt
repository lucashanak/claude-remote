package com.clauderemote.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    var speechRatePct by remember { mutableStateOf(settings.ttsSpeechRatePct) }
    var pitchPct by remember { mutableStateOf(settings.ttsPitchPct) }
    var systemVoice by remember { mutableStateOf(settings.ttsSystemVoice) }
    var systemVoices by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingSystemVoices by remember { mutableStateOf(false) }
    var wakeEnabled by remember { mutableStateOf(settings.wakeWordEnabled) }
    var wakeEngine by remember { mutableStateOf(settings.wakeEngine) }
    var wakePhrase by remember { mutableStateOf(settings.wakeWord) }
    var porcupineKey by remember { mutableStateOf(settings.porcupineAccessKey) }
    var porcupineKeyword by remember { mutableStateOf(settings.porcupineKeyword) }
    var sherpaKeyword by remember { mutableStateOf(settings.sherpaKeyword) }
    var sttEngine by remember { mutableStateOf(settings.sttEngine) }
    var sttTesting by remember { mutableStateOf(false) }
    var sttTestResult by remember { mutableStateOf("") }
    var pendingSttTest by remember { mutableStateOf(false) }
    val sttTestDictation = remember { java.util.concurrent.atomic.AtomicReference<ServerDictation?>(null) }

    DisposableEffect(Unit) { onDispose { sttTestDictation.get()?.stop() } }

    fun startServerSttTest() {
        val cfg = sttServerConfig(context)
        if (cfg.url.isBlank()) {
            Toast.makeText(context, "Není nastavená adresa serveru.", Toast.LENGTH_LONG).show()
            return
        }
        sttTesting = true; sttTestResult = ""
        val d = ServerDictation(
            context = context.applicationContext,
            baseUrl = cfg.url, model = cfg.model, apiKey = cfg.apiKey,
            continuous = false,
            onFinal = { text ->
                sttTestDictation.set(null); sttTesting = false
                sttTestResult = text.ifBlank { "(nic nerozpoznáno)" }
            },
            onError = { msg ->
                sttTestDictation.set(null); sttTesting = false
                Toast.makeText(context, "STT test: $msg", Toast.LENGTH_LONG).show()
            },
            onListening = { Toast.makeText(context, "Mluvte teď…", Toast.LENGTH_SHORT).show() },
        )
        sttTestDictation.set(d); d.start()
    }

    val sttPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingSttTest) startServerSttTest()
        else if (!granted) Toast.makeText(context, "Bez oprávnění mikrofonu nelze testovat.", Toast.LENGTH_LONG).show()
        pendingSttTest = false
    }
    val sttGoogleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        sttTesting = false
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        sttTestResult = text.ifBlank { "(nic nerozpoznáno)" }
    }

    fun runSttTest() {
        sttTestResult = ""
        if (sttEngine == SttEngine.SERVER) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startServerSttTest()
            else { pendingSttTest = true; sttPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        } else {
            // SYSTEM (Google): the system voice dialog activity.
            sttTesting = true
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, CZECH_LOCALE_TAG)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, CZECH_LOCALE_TAG)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Mluvte česky…")
            }
            runCatching { sttGoogleLauncher.launch(intent) }.onFailure {
                sttTesting = false
                Toast.makeText(context, "Nelze spustit hlasový dialog.", Toast.LENGTH_LONG).show()
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
        // ── STT (engine picker) ──────────────────────────────────────
        Text("Rozpoznávání řeči (STT)", style = CRType.cardTitle, color = c.text)
        ModelDropdown(
            label = "Engine",
            options = listOf(SttEngine.SYSTEM.displayName, SttEngine.SERVER.displayName),
            selected = sttEngine.displayName,
            onSelect = { name ->
                val picked = if (name == SttEngine.SYSTEM.displayName) SttEngine.SYSTEM else SttEngine.SERVER
                sttEngine = picked; settings.sttEngine = picked
            },
        )
        if (sttEngine == SttEngine.SYSTEM) {
            Text(
                "Google rozpoznávání přes Android. Na zařízeních bez české " +
                    "podpory v systému (např. HyperOS) se použije systémový " +
                    "Google hlasový dialog — stejný, co jede v Gboardu.",
                style = CRType.bodyDim, color = c.textDim,
            )
        }

        // Server — sdílené pro STT / TTS / aktivaci. URL + klíč vždy viditelné.
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
        if (sttEngine == SttEngine.SERVER) {
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
        }

        Button(
            onClick = { runSttTest() },
            enabled = !sttTesting,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
        ) {
            Text(if (sttTesting) "Poslouchám…" else "🎤 Otestovat rozpoznávání")
        }
        if (sttTestResult.isNotBlank()) {
            Text("Rozpoznáno: \"$sttTestResult\"", style = CRType.bodyDim, color = c.text)
        }

        androidx.compose.material3.HorizontalDivider(color = c.border)

        // ── Voice activation (wake word) ─────────────────────────────
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Aktivace hlasem", style = CRType.cardTitle, color = c.text)
                Text(
                    "Spustí dialog vyslovením klíčového slova. On-device " +
                        "(Porcupine) — offline, zvuk nikam neodchází. Jen když " +
                        "je appka vepředu.",
                    style = CRType.bodyDim, color = c.textDim,
                )
            }
            androidx.compose.material3.Switch(
                checked = wakeEnabled,
                onCheckedChange = { wakeEnabled = it; settings.wakeWordEnabled = it },
            )
        }
        if (wakeEnabled) {
            val serverLabel = "Server (Whisper)"
            val sherpaLabel = "On-device (sherpa, bez účtu)"
            val porcupineLabel = "Porcupine (on-device)"
            ModelDropdown(
                label = "Engine aktivace",
                options = listOf(serverLabel, sherpaLabel, porcupineLabel),
                selected = when (wakeEngine) {
                    "PORCUPINE" -> porcupineLabel
                    "SHERPA" -> sherpaLabel
                    else -> serverLabel
                },
                onSelect = { name ->
                    val picked = when (name) {
                        porcupineLabel -> "PORCUPINE"
                        sherpaLabel -> "SHERPA"
                        else -> "SERVER"
                    }
                    wakeEngine = picked; settings.wakeEngine = picked
                },
            )
            when (wakeEngine) {
                "SHERPA" -> {
                    ModelDropdown(
                        label = "Klíčové slovo",
                        options = SherpaKws.KEYWORDS.keys.toList(),
                        selected = sherpaKeyword,
                        onSelect = { sherpaKeyword = it; settings.sherpaKeyword = it },
                    )
                    Text(
                        "On-device, offline, bez účtu — model je v appce. Vyslov " +
                            "slovo anglicky. Mikrofon naslouchá průběžně (baterie), " +
                            "ale zvuk nikam neodchází.",
                        style = CRType.bodyDim, color = c.textDim,
                    )
                }
                "PORCUPINE" -> {
                    androidx.compose.material3.OutlinedTextField(
                        value = porcupineKey,
                        onValueChange = { porcupineKey = it; settings.porcupineAccessKey = it },
                        label = { Text("Picovoice AccessKey (z console.picovoice.ai)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ModelDropdown(
                        label = "Klíčové slovo",
                        options = listOf(
                            "JARVIS", "COMPUTER", "BUMBLEBEE", "PORCUPINE", "PICOVOICE",
                            "ALEXA", "TERMINATOR", "BLUEBERRY", "GRASSHOPPER",
                        ),
                        selected = porcupineKeyword,
                        onSelect = { porcupineKeyword = it; settings.porcupineKeyword = it },
                    )
                    Text(
                        "On-device, offline. Vyslov slovo anglicky. Vlastní " +
                            "„Hey Claude“ jde natrénovat (.ppn). Pozn.: registrace " +
                            "Picovoice může chtít firemní e-mail.",
                        style = CRType.bodyDim, color = c.textDim,
                    )
                }
                else -> {
                    androidx.compose.material3.OutlinedTextField(
                        value = wakePhrase,
                        onValueChange = { wakePhrase = it; settings.wakeWord = it },
                        label = { Text("Aktivační fráze (např. claude / hej claude)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Poslouchá přes STT server (vyžaduje nastavenou adresu). " +
                            "Funguje v češtině, ale mikrofon posílá každou větu na " +
                            "server (baterie/data).",
                        style = CRType.bodyDim, color = c.textDim,
                    )
                }
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

        // Reading speed — applies to every engine.
        PercentSlider(
            label = "Rychlost čtení",
            valuePct = speechRatePct,
            range = 50..300,
            onChange = { speechRatePct = it; settings.ttsSpeechRatePct = it },
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
                PercentSlider(
                    label = "Výška hlasu",
                    valuePct = pitchPct,
                    range = 50..200,
                    onChange = { pitchPct = it; settings.ttsPitchPct = it },
                )
                if (systemVoices.isNotEmpty()) {
                    ModelDropdown(
                        label = "Hlas zařízení",
                        options = listOf("(výchozí)") + systemVoices,
                        selected = systemVoice.ifBlank { "(výchozí)" },
                        onSelect = {
                            val v = if (it == "(výchozí)") "" else it
                            systemVoice = v; settings.ttsSystemVoice = v
                        },
                    )
                }
                Button(
                    onClick = {
                        loadingSystemVoices = true
                        SystemTtsVoices.load(
                            context,
                            onResult = { names ->
                                loadingSystemVoices = false
                                systemVoices = names
                                if (names.isEmpty()) {
                                    Toast.makeText(context, "Zařízení nehlásí žádný český hlas.", Toast.LENGTH_LONG).show()
                                }
                            },
                            onError = { msg ->
                                loadingSystemVoices = false
                                Toast.makeText(context, "Hlasy zařízení: $msg", Toast.LENGTH_LONG).show()
                            },
                        )
                    },
                    enabled = !loadingSystemVoices,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.accentInk),
                ) {
                    Text(
                        if (loadingSystemVoices) "Načítám…"
                        else if (systemVoices.isEmpty()) "Načíst hlasy zařízení"
                        else "Obnovit hlasy (${systemVoices.size})"
                    )
                }
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

/** Labeled slider that edits an integer-percent value (100 = 1.0x), snapped
 * to multiples of 5. */
@Composable
private fun PercentSlider(
    label: String,
    valuePct: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    val c = CRTheme.colors
    val step = 5
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("$label — $valuePct %", style = CRType.bodyDim, color = c.textDim)
        androidx.compose.material3.Slider(
            value = valuePct.coerceIn(range.first, range.last).toFloat(),
            onValueChange = { onChange(Math.round(it)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
        )
    }
}

@Composable
actual fun WakeWordListener(paused: Boolean, onWake: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> foreground = true
                Lifecycle.Event.ON_PAUSE -> foreground = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Re-read opt-in + engine each composition so toggling in settings takes
    // effect when this screen recomposes.
    val prefs = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean("wake_word_enabled", false)
    val engine = prefs.getString("wake_engine", "SERVER").orEmpty().ifBlank { "SERVER" }
    val active = enabled && !paused && foreground

    // Each engine is its own composable; it's only in composition while active,
    // so its DisposableEffect start/stop tracks the gate automatically.
    if (active) {
        when (engine) {
            "PORCUPINE" -> PorcupineWake(onWake)
            "SHERPA" -> SherpaWake(onWake)
            else -> ServerWake(onWake) // SERVER (Whisper VAD) — the default
        }
    }
}

/** On-device wake via sherpa-onnx KWS (offline, no account; bundled model). */
@Composable
private fun SherpaWake(onWake: () -> Unit) {
    val context = LocalContext.current
    val onWakeState = rememberUpdatedState(onWake)
    val holder = remember { java.util.concurrent.atomic.AtomicReference<SherpaKws?>(null) }
    val prefs = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    val word = prefs.getString("sherpa_keyword", "HEY CLAUDE").orEmpty().ifBlank { "HEY CLAUDE" }
    val tokens = SherpaKws.KEYWORDS[word] ?: SherpaKws.KEYWORDS.values.first()
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    DisposableEffect(tokens, hasPermission) {
        if (!hasPermission) {
            onDispose { }
        } else {
            val kws = SherpaKws(
                context = context.applicationContext,
                keywordTokens = tokens,
                onWake = { onWakeState.value() },
                onError = { msg -> Toast.makeText(context, "Wake (KWS): $msg", Toast.LENGTH_LONG).show() },
            )
            holder.set(kws)
            kws.start()
            onDispose { kws.stop(); holder.set(null) }
        }
    }
}

/** Server-side wake: continuous [ServerDictation] + phrase match on Whisper. */
@Composable
private fun ServerWake(onWake: () -> Unit) {
    val context = LocalContext.current
    val onWakeState = rememberUpdatedState(onWake)
    val holder = remember { java.util.concurrent.atomic.AtomicReference<ServerDictation?>(null) }
    val prefs = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    val phrase = normalizeWake(
        prefs.getString("wake_word_phrase", "claude").orEmpty().ifBlank { "claude" }
    )
    val cfg = sttServerConfig(context)
    DisposableEffect(cfg.url, phrase) {
        if (cfg.url.isBlank()) {
            onDispose { }
        } else {
            val dictation = ServerDictation(
                context = context.applicationContext,
                baseUrl = cfg.url, model = cfg.model, apiKey = cfg.apiKey,
                continuous = true,
                onFinal = { text ->
                    if (matchesWake(text, phrase)) {
                        holder.get()?.stop()
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ onWakeState.value() }, 300)
                    }
                },
                onError = { /* best-effort; mic may be briefly busy */ },
            )
            holder.set(dictation)
            dictation.start()
            onDispose { dictation.stop(); holder.set(null) }
        }
    }
}

/** On-device wake via Picovoice Porcupine (offline; AccessKey = license only). */
@Composable
private fun PorcupineWake(onWake: () -> Unit) {
    val context = LocalContext.current
    val onWakeState = rememberUpdatedState(onWake)
    val prefs = context.getSharedPreferences("claude_remote", Context.MODE_PRIVATE)
    val accessKey = prefs.getString("porcupine_access_key", "").orEmpty()
    val keywordName = prefs.getString("porcupine_keyword", "JARVIS").orEmpty().ifBlank { "JARVIS" }
    val hasPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    DisposableEffect(accessKey, keywordName, hasPermission) {
        if (accessKey.isBlank() || !hasPermission) {
            onDispose { }
        } else {
            val keyword = runCatching {
                ai.picovoice.porcupine.Porcupine.BuiltInKeyword.valueOf(keywordName)
            }.getOrDefault(ai.picovoice.porcupine.Porcupine.BuiltInKeyword.JARVIS)
            val manager = runCatching {
                ai.picovoice.porcupine.PorcupineManager.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(keyword)
                    .setSensitivity(0.6f)
                    .build(context.applicationContext) { _ ->
                        postOnMain { onWakeState.value() }
                    }
            }.getOrElse { e ->
                Toast.makeText(
                    context,
                    "Wake (Porcupine): ${e.message ?: "init selhal — zkontroluj AccessKey"}",
                    Toast.LENGTH_LONG,
                ).show()
                null
            }
            if (manager != null) {
                runCatching { manager.start() }.onFailure {
                    Toast.makeText(context, "Wake: nelze spustit mikrofon", Toast.LENGTH_LONG).show()
                }
            }
            onDispose {
                runCatching { manager?.stop() }
                runCatching { manager?.delete() }
            }
        }
    }
}

/** Lowercase + strip diacritics so "Hej Claude" matches phrase "claude". */
private fun normalizeWake(s: String): String =
    java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .trim()

private fun matchesWake(transcript: String, normalizedPhrase: String): Boolean {
    if (normalizedPhrase.isBlank()) return false
    return normalizeWake(transcript).contains(normalizedPhrase)
}
