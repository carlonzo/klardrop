package com.carlom.klardrop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// KdRadii — corner radius tokens + corresponding shapes from spec/tokens.css
// ---------------------------------------------------------------------------

data class KdRadii(
    /** 6 dp — status dot outline, chip tints */
    val xs: Dp = 6.dp,
    /** 10 dp — file icon tile inside a file card */
    val sm: Dp = 10.dp,
    /** 14 dp — buttons, banners, selected state */
    val md: Dp = 14.dp,
    /** 18 dp — device row, chat bubble, file card */
    val lg: Dp = 18.dp,
    /** 24 dp — bottom sheet, dialog, hero card */
    val xl: Dp = 24.dp,

    /** Fully-rounded: visibility pill, message input, send button */
    val shapeXs: RoundedCornerShape = RoundedCornerShape(6.dp),
    val shapeSm: RoundedCornerShape = RoundedCornerShape(10.dp),
    val shapeMd: RoundedCornerShape = RoundedCornerShape(14.dp),
    val shapeLg: RoundedCornerShape = RoundedCornerShape(18.dp),
    val shapeXl: RoundedCornerShape = RoundedCornerShape(24.dp),
    val shapePill: RoundedCornerShape = RoundedCornerShape(50),
)

val KdDefaultRadii = KdRadii()
