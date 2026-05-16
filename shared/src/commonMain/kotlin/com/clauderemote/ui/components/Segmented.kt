package com.clauderemote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clauderemote.ui.theme.CRTheme
import com.clauderemote.ui.theme.CRType

@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    val outer = RoundedCornerShape(10.dp)
    Row(
        modifier
            .background(CRTheme.colors.surface2, outer)
            .border(1.dp, CRTheme.colors.border, outer)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { opt ->
            val active = opt == selected
            val inner = RoundedCornerShape(8.dp)
            Text(
                label(opt),
                style = CRType.pill,
                color = if (active) CRTheme.colors.accentInk else CRTheme.colors.textDim,
                modifier = Modifier
                    .clickable { onSelect(opt) }
                    .background(if (active) CRTheme.colors.accent else androidx.compose.ui.graphics.Color.Transparent, inner)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}
