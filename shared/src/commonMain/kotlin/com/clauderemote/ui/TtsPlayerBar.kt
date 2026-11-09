package com.clauderemote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType
import com.clauderemote.voice.TtsPhase
import com.clauderemote.voice.TtsPlayback

/**
 * Slim transport bar for read-aloud (Soniox, Android and desktop): previous / -10 s /
 * play-pause / next paragraph / close, plus where in the message we are.
 * Invisible whenever nothing is being read, so it costs nothing elsewhere.
 * On Android the same controls are on the media notification, the lock screen
 * and the car's / headset's media buttons (MediaSession); desktop has the bar only.
 */
@Composable
fun TtsPlayerBar(modifier: Modifier = Modifier) {
    val state by TtsPlayback.state.collectAsState()
    val c = CRTheme.colors
    AnimatedVisibility(visible = state != null, modifier = modifier) {
        val s = state ?: return@AnimatedVisibility
        val ctl = TtsPlayback.controller
        Surface(color = c.surface, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    IconButton(onClick = { ctl?.previous() }) {
                        Icon(Icons.Filled.SkipPrevious, "Předchozí odstavec", tint = c.text)
                    }
                    IconButton(onClick = { ctl?.rewind(10) }) {
                        Icon(Icons.Filled.Replay10, "Zpět o 10 s", tint = c.text)
                    }
                    val playing = s.phase == TtsPhase.PLAYING ||
                        s.phase == TtsPhase.BUFFERING || s.phase == TtsPhase.WAITING_NETWORK
                    IconButton(onClick = { if (playing) ctl?.pause() else ctl?.play() }) {
                        if (s.phase == TtsPhase.BUFFERING) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = c.accent,
                            )
                        } else {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) "Pauza" else "Přehrát",
                                tint = c.accent,
                            )
                        }
                    }
                    IconButton(onClick = { ctl?.next() }) {
                        Icon(Icons.Filled.SkipNext, "Další odstavec", tint = c.text)
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        val status = when (s.phase) {
                            TtsPhase.BUFFERING -> "načítám…"
                            TtsPhase.PLAYING -> "čtu"
                            TtsPhase.PAUSED -> "pauza"
                            TtsPhase.WAITING_NETWORK -> "čekám na signál…"
                            TtsPhase.INTERRUPTED -> s.note ?: "přerušeno"
                        }
                        Text(
                            "Odstavec ${s.segment + 1}/${s.segmentCount} · $status",
                            style = CRType.mono,
                            color = if (s.phase == TtsPhase.INTERRUPTED ||
                                s.phase == TtsPhase.WAITING_NETWORK
                            ) c.tintOrange else c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            s.preview,
                            style = CRType.bodyDim,
                            color = c.textDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { ctl?.stop() }) {
                        Icon(Icons.Filled.Close, "Ukončit čtení", tint = c.textDim)
                    }
                }
                HorizontalDivider(color = c.border)
            }
        }
    }
}
