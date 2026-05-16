package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRStatusViz
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

enum class CRStatus { Working, Ready, Approval, Idle, Disconnected }

@Composable
fun CRStatus.color(): Color = when (this) {
    CRStatus.Working -> CRTheme.colors.working
    CRStatus.Ready -> CRTheme.colors.ready
    CRStatus.Approval -> CRTheme.colors.approval
    CRStatus.Idle -> CRTheme.colors.idle
    CRStatus.Disconnected -> CRTheme.colors.disconnected
}

@Composable
fun CRStatus.label(): String = when (this) {
    CRStatus.Working -> "Working"
    CRStatus.Ready -> "Ready"
    CRStatus.Approval -> "Approval"
    CRStatus.Idle -> "Idle"
    CRStatus.Disconnected -> "Offline"
}

@Composable
fun StatusIndicator(
    status: CRStatus,
    modifier: Modifier = Modifier,
    viz: CRStatusViz = CRTheme.statusViz,
) {
    val c = status.color()
    when (viz) {
        CRStatusViz.Dot -> Box(modifier.size(8.dp).background(c, CircleShape))
        CRStatusViz.Pill -> Row(
            modifier
                .background(c.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(c, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(status.label(), style = CRType.pill, color = c)
        }
        CRStatusViz.Bar -> Box(
            modifier
                .height(3.dp)
                .fillMaxWidth()
                .background(c, RoundedCornerShape(2.dp))
        )
    }
}
