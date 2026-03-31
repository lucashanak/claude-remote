package com.clauderemote

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.App
import dev.datlag.kcef.KCEF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun main() = application {
    val prefs = PlatformPreferences()
    val serverStorage = ServerStorage(prefs)
    val appSettings = AppSettings(prefs)
    val tabManager = TabManager()
    val sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

    var kcefReady by remember { mutableStateOf(false) }
    var kcefError by remember { mutableStateOf<String?>(null) }

    // Initialize KCEF (Chromium) on startup
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                KCEF.init(builder = {
                    installDir(File(System.getProperty("user.home"), ".claude-remote/kcef"))
                    progress {
                        onInitialized { kcefReady = true }
                    }
                })
            } catch (e: Exception) {
                kcefError = e.message
                // Allow app to work without KCEF (terminal won't render)
                kcefReady = true
            }
        }
    }

    Window(
        onCloseRequest = {
            KCEF.dispose()
            exitApplication()
        },
        title = "Claude Remote",
        state = rememberWindowState(width = 1000.dp, height = 700.dp)
    ) {
        if (!kcefReady) {
            // Loading screen while KCEF downloads/initializes Chromium
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFBB86FC))
                Text(
                    kcefError ?: "Initializing browser engine...",
                    color = Color(0xFF888888),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } else {
            App(
                serverStorage = serverStorage,
                appSettings = appSettings,
                tabManager = tabManager,
                sessionOrchestrator = sessionOrchestrator,
                appVersion = "1.0.0",
                terminalContent = { modifier ->
                    DesktopTerminal(modifier = modifier)
                }
            )
        }
    }
}

@Composable
fun DesktopTerminal(modifier: Modifier) {
    var initialized by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        try {
            // Load xterm.js terminal HTML via KCEF WebView
            val terminalHtml = object {}.javaClass.getResource("/terminal/terminal.html")

            if (terminalHtml != null) {
                dev.datlag.kcef.KCEFBrowser(
                    url = terminalHtml.toString(),
                    modifier = Modifier.fillMaxSize()
                )
                initialized = true
            } else {
                Text(
                    "Terminal HTML not found in resources",
                    color = Color(0xFFFF4444),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        } catch (e: Exception) {
            Text(
                "Terminal error: ${e.message}",
                color = Color(0xFFFF4444),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
