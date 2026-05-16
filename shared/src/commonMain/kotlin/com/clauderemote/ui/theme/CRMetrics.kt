package com.clauderemote.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class CRMetrics(
    val cardPad: Dp,
    val cardPadV: Dp,
    val cardPadH: Dp,
    val cardGap: Dp,
    val cardRadius: Dp,
    val sectionPad: Dp,
    val sectionTopGap: Dp,
    val rowHeight: Dp,
    val inputPad: Dp,
    val showPreviewLine: Boolean,
    val showHeatmap: Boolean,
    val sessionCardOneLine: Boolean,
) {
    companion object {
        fun forDensity(d: CRDensity): CRMetrics = when (d) {
            CRDensity.Compact -> CRMetrics(
                cardPad = 12.dp, cardPadV = 10.dp, cardPadH = 12.dp,
                cardGap = 8.dp, cardRadius = 10.dp,
                sectionPad = 12.dp, sectionTopGap = 14.dp,
                rowHeight = 48.dp, inputPad = 8.dp,
                showPreviewLine = true, showHeatmap = true,
                sessionCardOneLine = false,
            )
            CRDensity.Regular -> CRMetrics(
                cardPad = 16.dp, cardPadV = 14.dp, cardPadH = 16.dp,
                cardGap = 12.dp, cardRadius = 12.dp,
                sectionPad = 16.dp, sectionTopGap = 16.dp,
                rowHeight = 56.dp, inputPad = 10.dp,
                showPreviewLine = true, showHeatmap = true,
                sessionCardOneLine = false,
            )
            CRDensity.Dense -> CRMetrics(
                cardPad = 8.dp, cardPadV = 6.dp, cardPadH = 10.dp,
                cardGap = 4.dp, cardRadius = 8.dp,
                sectionPad = 10.dp, sectionTopGap = 8.dp,
                rowHeight = 38.dp, inputPad = 6.dp,
                showPreviewLine = false, showHeatmap = false,
                sessionCardOneLine = true,
            )
        }
    }
}
