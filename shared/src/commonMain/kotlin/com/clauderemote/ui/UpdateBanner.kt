package com.clauderemote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.util.UpdateChecker
import com.clauderemote.util.UpdateInfo

data class UpdateState(
    val info: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Int = 0,
    val statusText: String = "",
    val error: String? = null
)

@Composable
fun UpdateBanner(
    state: UpdateState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.info != null,
        modifier = modifier
    ) {
        Surface(
            color = Color(0xFF4CAF50),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val info = state.info
                        if (state.downloading) {
                            Text(
                                state.statusText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else if (state.error != null) {
                            Text(
                                state.error,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        } else if (info != null) {
                            val onAndroid = try { Class.forName("android.os.Build"); true } catch (_: Exception) { false }
                            val dlSize = when {
                                onAndroid && info.hasPatch -> info.totalPatchSize
                                onAndroid -> info.apkSize
                                info.dmgSize > 0 -> info.dmgSize
                                else -> info.apkSize
                            }
                            val sizeStr = UpdateChecker.formatBytes(dlSize)
                            val patchInfo = if (onAndroid && info.hasPatch) " (${info.patchChain.size}x patch)" else ""
                            Text(
                                "Update v${info.version} available ($sizeStr$patchInfo)",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (!state.downloading) {
                        if (state.error != null) {
                            TextButton(onClick = onDownload) {
                                Text("Retry", color = Color.White)
                            }
                        } else {
                            TextButton(onClick = onDownload) {
                                Text("Install", color = Color.White, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        TextButton(onClick = onDismiss) {
                            Text("X", color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }

                if (state.downloading) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}
