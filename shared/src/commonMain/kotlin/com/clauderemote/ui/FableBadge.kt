package com.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.model.ClaudeModel
import com.clauderemote.model.ClaudeSession
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

/**
 * Whether [session] is answering with Fable right now. [liveModelId] — the
 * model of its newest transcript answer — wins when known, because /model
 * changes it without touching the tab; the launch model only covers a session
 * that hasn't answered yet.
 */
internal fun runsFable(session: ClaudeSession, liveModelId: String?): Boolean =
    liveModelId?.contains("fable", ignoreCase = true) ?: (session.model == ClaudeModel.FABLE)

/** Small marker on a session row whose active model is Fable. */
@Composable
internal fun FableBadge(modifier: Modifier = Modifier) {
    val c = CRTheme.colors
    Text(
        "fable",
        style = CRType.monoTiny,
        color = c.modePlan,
        maxLines = 1,
        modifier = modifier
            .background(c.tintPurple, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
