package com.carlom.klardrop.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// KdElevation — elevation levels from spec/tokens.css
//
// Note: Compose's shadow() maps to a single box-shadow whereas the spec defines
// multi-layer CSS shadows (inset highlight + drop shadow).  We approximate with
// a single-layer shadow at the corresponding opacity depth.  The inset
// top-edge highlight is intentionally omitted — Compose has no inset shadow.
// ---------------------------------------------------------------------------

/** Row hover · subtle bubble shadow */
val KdE1: Dp = 2.dp

/** Cards · share sheet */
val KdE2: Dp = 8.dp

/** Pairing dialog · drop overlay · phone preview */
val KdE3: Dp = 20.dp

/**
 * Convenience modifier that applies a KD-levelled shadow so callers
 * don't repeat `shadow(elevation, shape)`.
 *
 * @param level 1, 2, or 3  (anything else → no shadow)
 * @param shape the clipping shape for the shadow
 */
fun Modifier.kdElevation(level: Int, shape: RoundedCornerShape = RoundedCornerShape(0.dp)): Modifier {
    val elevation = when (level) {
        1 -> KdE1
        2 -> KdE2
        3 -> KdE3
        else -> return this
    }
    return this.shadow(elevation = elevation, shape = shape)
}
