package com.carlom.klardrop.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C03 · StatusDot
// ---------------------------------------------------------------------------

enum class KdStatus { Ok, Warn, Err }

/**
 * 8 dp coloured dot with a soft 18%-alpha halo.
 * Warn status pulses every 1.6 s.
 *
 * @param status        Ok / Warn / Err
 * @param dotSize       diameter of the solid inner dot (default 8 dp)
 * @param outlineWidth  outline around dot separating it from the surface (default 2 dp)
 * @param outlineColor  color of the surface outline ring (defaults to transparent)
 * @param modifier      applied to the outermost Box
 */
@Composable
fun StatusDot(
    status: KdStatus,
    dotSize: Dp = 8.dp,
    outlineWidth: Dp = 0.dp,
    outlineColor: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val motion = KdTheme.motion

    val dotColor = when (status) {
        KdStatus.Ok   -> colors.ok
        KdStatus.Warn -> colors.warn
        KdStatus.Err  -> colors.err
    }

    // Pulse animation — only active for Warn
    val haloAlpha by if (status == KdStatus.Warn) {
        rememberInfiniteTransition(label = "warn-pulse").animateFloat(
            initialValue = 0.18f,
            targetValue  = 0.05f,
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = motion.dBase, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "halo-alpha",
        )
    } else {
        // Static value for Ok / Err
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.18f) }
    }

    val haloColor = dotColor.copy(alpha = haloAlpha)
    val haloRadius = dotSize / 2 + dotSize * 0.4f  // halo ~3 px wider each side

    // Outer Box sized to accommodate halo + outline ring
    val totalSize = dotSize + outlineWidth * 2
    Box(
        modifier = modifier
            .size(totalSize)
            .drawBehind {
                // Halo drawn behind everything
                drawCircle(color = haloColor, radius = haloRadius.toPx() + outlineWidth.toPx())
            },
        contentAlignment = Alignment.Center,
    ) {
        // Surface outline ring (separates dot from avatar fill)
        if (outlineWidth > 0.dp) {
            Box(
                modifier = Modifier
                    .size(dotSize + outlineWidth * 2)
                    .clip(CircleShape)
                    .background(outlineColor),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }
    }
}
