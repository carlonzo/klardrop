package com.carlom.klardrop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C04 · VisibilityPill
// ---------------------------------------------------------------------------

sealed class KdVisibilityState {
    /** Device is broadcasting on the given Wi-Fi SSID */
    data class Visible(val ssid: String) : KdVisibilityState()
    /** Visibility is off */
    object Hidden : KdVisibilityState()
}

/**
 * 32 dp pill showing the Wi-Fi visibility status.
 *
 * @param state     Visible(ssid) or Hidden
 * @param onTap     callback when tapped (opens visibility settings)
 * @param modifier  applied to the Surface
 */
@Composable
fun VisibilityPill(
    state: KdVisibilityState,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val borderColor = when (state) {
        is KdVisibilityState.Visible -> colors.border
        KdVisibilityState.Hidden     -> colors.err
    }

    Surface(
        modifier = modifier
            .height(32.dp)
            .clickable(onClick = onTap),
        shape = radii.shapePill,
        color = colors.bg1,
        border = BorderStroke(width = 1.dp, color = borderColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.s3, vertical = spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading dot
            val dotStatus = when (state) {
                is KdVisibilityState.Visible -> KdStatus.Ok
                KdVisibilityState.Hidden     -> KdStatus.Err
            }
            StatusDot(status = dotStatus, dotSize = 6.dp)

            Spacer(Modifier.width(spacing.s2))

            val label = when (state) {
                is KdVisibilityState.Visible -> state.ssid
                KdVisibilityState.Hidden     -> "Hidden"
            }
            Text(
                text = label,
                style = typography.caption.copy(color = colors.text2),
                maxLines = 1,
            )
        }
    }
}
