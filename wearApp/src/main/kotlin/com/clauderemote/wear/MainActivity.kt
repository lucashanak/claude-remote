package com.clauderemote.wear

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.serialization.decodeFromString

/**
 * Session list — the watch's home screen. Reads [SessionRepository] (kept
 * current by [WearDataListenerService]) and seeds it once on launch via a
 * direct DataClient fetch, since onDataChanged only fires for NEW pushes —
 * if the phone already pushed before this activity started, we'd otherwise
 * show nothing until the next session-state change on the phone.
 *
 * Tapping a row does nothing yet — SessionDetail (reply/approve/read-aloud)
 * is the next PR.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fetchInitialSessions(applicationContext)
        setContent {
            MaterialTheme {
                SessionListScreen()
            }
        }
    }
}

@Composable
private fun SessionListScreen() {
    val sessions by SessionRepository.sessions.collectAsState()

    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No sessions yet")
        }
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sessions.forEach { session ->
            item { SessionRow(session) }
        }
    }
}

@Composable
private fun SessionRow(session: WearSessionInfo) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(activityColor(session.activity)),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(session.title, maxLines = 1)
    }
}

private fun activityColor(activity: String): Color = when (activity) {
    "WORKING" -> Color(0xFFFFB44E)
    "WAITING_FOR_INPUT" -> Color(0xFF4E9CFF)
    "APPROVAL_NEEDED" -> Color(0xFFFF5C5C)
    "DISCONNECTED" -> Color(0xFF6F7E96)
    else -> Color(0xFF4EE0A0) // IDLE
}

/**
 * One-shot fetch of the current "/sessions" DataItem (from any node, hence
 * host "*") so a freshly-launched watch app isn't empty until the phone's
 * next state change. Best-effort; failures just leave the list empty until
 * onDataChanged fires.
 */
private fun fetchInitialSessions(context: Context) {
    val uri = Uri.Builder().scheme("wear").authority("*").path(WearDataListenerService.PATH).build()
    Wearable.getDataClient(context).getDataItems(uri)
        .addOnSuccessListener { buffer ->
            runCatching {
                val item = if (buffer.count > 0) buffer[0] else null
                val json = item?.let { DataMapItem.fromDataItem(it).dataMap.getString(WearDataListenerService.KEY_JSON) }
                if (json != null) {
                    val payload = WearDataListenerService.WEAR_JSON.decodeFromString<WearSessionsPayload>(json)
                    SessionRepository.update(payload.sessions)
                }
            }
            buffer.release()
        }
}
