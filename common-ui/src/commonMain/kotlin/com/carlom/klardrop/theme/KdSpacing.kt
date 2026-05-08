package com.carlom.klardrop.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// KdSpacing — 4-pt grid scale from spec/tokens.css
// ---------------------------------------------------------------------------

data class KdSpacing(
    val s1: Dp = 4.dp,
    val s2: Dp = 8.dp,
    val s3: Dp = 12.dp,
    val s4: Dp = 16.dp,
    val s5: Dp = 20.dp,
    val s6: Dp = 24.dp,
    val s7: Dp = 32.dp,
    val s8: Dp = 40.dp,
    val s9: Dp = 48.dp,
)

val KdDefaultSpacing = KdSpacing()
