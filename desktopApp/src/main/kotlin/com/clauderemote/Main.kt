package com.clauderemote

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import com.clauderemote.session.SessionOrchestrator
import com.clauderemote.session.TabManager
import com.clauderemote.storage.AppSettings
import com.clauderemote.storage.PlatformPreferences
import com.clauderemote.storage.ServerStorage
import com.clauderemote.ui.App
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color

fun main() = application {
    val prefs = PlatformPreferences()
    val serverStorage = ServerStorage(prefs)
    val appSettings = AppSettings(prefs)
    val tabManager = TabManager()
    val sessionOrchestrator = SessionOrchestrator(serverStorage, tabManager)

    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Remote",
        state = rememberWindowState(width = 900.dp, height = 700.dp)
    ) {
        App(
            serverStorage = serverStorage,
            appSettings = appSettings,
            tabManager = tabManager,
            sessionOrchestrator = sessionOrchestrator,
            terminalContent = { modifier ->
                // Desktop terminal placeholder
                // TODO: integrate JCEF or compose-webview for xterm.js
                DesktopTerminalPlaceholder(modifier)
            }
        )
    }
}

@Composable
private fun DesktopTerminalPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        Text(
            "Terminal (xterm.js via JCEF - integration pending)",
            color = Color(0xFF888888),
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center)
        )
    }
}
