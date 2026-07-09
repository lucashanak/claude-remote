package com.clauderemote.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.Wearable

/**
 * PR B1 — Data Layer "ping": proves a [com.google.android.gms.wearable.MessageClient]
 * round trip actually reaches the phone's PhoneWearService before any real
 * session-sync logic is built on top. Session list / detail land in later PRs.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                PingScreen()
            }
        }
    }
}

@Composable
private fun PingScreen() {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Claude Remote") }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Text(status, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        item {
            Button(onClick = { status = "Sending…"; sendPing(context) { status = it } }) {
                Text("Ping phone")
            }
        }
    }
}

/**
 * Fire-and-forget MessageClient round trip to the phone's PhoneWearService.
 * Callback-based (no coroutines-play-services dependency needed) — mirrors
 * the plain GMS Task listener style used in the official samples.
 */
private fun sendPing(context: android.content.Context, onResult: (String) -> Unit) {
    val nodeClient = Wearable.getNodeClient(context)
    nodeClient.connectedNodes
        .addOnSuccessListener { nodes ->
            val node = nodes.firstOrNull()
            if (node == null) {
                onResult("No phone connected")
                return@addOnSuccessListener
            }
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/ping", "hello from watch".toByteArray(Charsets.UTF_8))
                .addOnSuccessListener { onResult("Sent to ${node.displayName}") }
                .addOnFailureListener { e -> onResult("Send failed: ${e.message}") }
        }
        .addOnFailureListener { e -> onResult("Node lookup failed: ${e.message}") }
}
