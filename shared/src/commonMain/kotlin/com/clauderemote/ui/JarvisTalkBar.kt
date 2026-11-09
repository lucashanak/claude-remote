package com.clauderemote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.voice.conversation.JarvisTalk
import com.clauderemote.voice.conversation.TalkPhase

/**
 * Jarvis' conversation mode in the app (F5a): the state line (poslouchám / posílám / Jarvis přemýšlí / mluví), what
 * was heard last, and the buttons. App-wide under the read-aloud bar while the mode runs (so it is visible on any
 * screen) and on Jarvis' own tab; [JarvisTalkRow] is the same inside the Velín's Jarvis card. Draws nothing on a
 * platform without a host ([JarvisTalk.host]).
 */
@Composable
fun JarvisTalkBar(jarvisShown: Boolean, modifier: Modifier = Modifier) {
    if (JarvisTalk.host == null) return
    val s by JarvisTalk.state.collectAsState()
    val c = CRTheme.colors
    AnimatedVisibility(visible = s.phase != TalkPhase.OFF || jarvisShown, modifier = modifier) {
        Surface(color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column {
                JarvisTalkRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                HorizontalDivider(color = c.border)
            }
        }
    }
}

@Composable
fun JarvisTalkRow(modifier: Modifier = Modifier) {
    if (JarvisTalk.host == null) return
    val s by JarvisTalk.state.collectAsState()
    val problem by JarvisTalk.problem.collectAsState()
    val c = CRTheme.colors
    val on = s.phase != TalkPhase.OFF
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                JarvisTalk.label(s),
                style = CRType.bodyDim,
                color = if (on) c.accent else c.textDim,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (on) {
                // the same as the headset's button: finish the utterance / interrupt the reading / talk while he thinks
                val action = when (s.phase) {
                    TalkPhase.LISTENING -> "Odeslat"
                    TalkPhase.SPEAKING -> "Přerušit"
                    TalkPhase.THINKING -> "Mluvit"
                    else -> null
                }
                if (action != null) TextButton(onClick = { JarvisTalk.button() }) { Text(action, color = c.accent) }
                TextButton(onClick = { JarvisTalk.stop() }) { Text("Ukončit", color = c.disconnected) }
            } else {
                OutlinedButton(onClick = { JarvisTalk.start() }) { Text("🎙 Mluvit s Jarvisem") }
            }
        }
        if (on) s.heard?.let { Text("„$it“", style = CRType.monoTiny, color = c.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (on) s.notice?.takeIf { s.phase == TalkPhase.SPEAKING }?.let { Text(it, style = CRType.monoTiny, color = c.textDim) }
        problem?.takeIf { !on }?.let { Text(it, style = CRType.monoTiny, color = c.disconnected) }
    }
}
