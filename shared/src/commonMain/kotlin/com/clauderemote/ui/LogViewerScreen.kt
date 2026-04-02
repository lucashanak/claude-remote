package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clauderemote.util.FileLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    var logContent by remember { mutableStateOf(FileLogger.readLog()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { logContent = FileLogger.readLog() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                    IconButton(onClick = {
                        FileLogger.clearLog()
                        logContent = FileLogger.readLog()
                    }) {
                        Icon(Icons.Default.Delete, "Clear")
                    }
                }
            )
        }
    ) { padding ->
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()

        // Auto-scroll to bottom on load
        LaunchedEffect(logContent) {
            verticalScroll.animateScrollTo(verticalScroll.maxValue)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1E1E1E))
        ) {
            Text(
                text = logContent,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(verticalScroll)
                    .horizontalScroll(horizontalScroll)
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFFCCCCCC),
                lineHeight = 16.sp
            )
        }
    }
}
