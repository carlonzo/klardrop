package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme
import com.klardrop.resources.Res
import com.klardrop.resources.laptop
import com.klardrop.resources.mobile
import org.jetbrains.compose.resources.painterResource

// ---------------------------------------------------------------------------
// C01 · DeviceAvatar
// ---------------------------------------------------------------------------

enum class KdDeviceKind { Mac, Iphone, Android, Pc, Tablet, Unknown }

enum class KdAvatarStyle {
    /** Sage tint — used for "Your devices" (trusted) */
    Tinted,
    /** Neutral bg — used for "Nearby" strangers */
    Neutral,
}

/**
 * @param kind         platform glyph to render
 * @param style        Tinted (trust color) or Neutral (bg2)
 * @param status       optional status dot overlay; null = no dot
 * @param size         avatar circle diameter; one of 32 / 36 / 40 / 48 / 64 / 84 dp
 * @param modifier     applied to the outermost Box
 */
@Composable
fun DeviceAvatar(
    kind: KdDeviceKind,
    style: KdAvatarStyle = KdAvatarStyle.Neutral,
    status: KdStatus? = null,
    size: Dp = 48.dp,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors

    val fillColor = when (style) {
        KdAvatarStyle.Tinted  -> colors.trustBg
        KdAvatarStyle.Neutral -> colors.bg2
    }
    val glyphColor = when (style) {
        KdAvatarStyle.Tinted  -> colors.trust
        KdAvatarStyle.Neutral -> colors.text2
    }

    // Status dot size scales with avatar size
    val dotSize: Dp = if (size <= 32.dp) 8.dp else 10.dp
    val dotOutline: Dp = 2.dp

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        // Avatar circle + glyph
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(fillColor),
            contentAlignment = Alignment.Center,
        ) {
            val glyphSize = (size.value * 0.45f).dp
            when (kind) {
                KdDeviceKind.Mac,
                KdDeviceKind.Pc,
                KdDeviceKind.Tablet -> Icon(
                    painter = painterResource(Res.drawable.laptop),
                    contentDescription = kind.name,
                    modifier = Modifier.size(glyphSize),
                    tint = glyphColor,
                )
                KdDeviceKind.Iphone,
                KdDeviceKind.Android -> Icon(
                    painter = painterResource(Res.drawable.mobile),
                    contentDescription = kind.name,
                    modifier = Modifier.size(glyphSize),
                    tint = glyphColor,
                )
                KdDeviceKind.Unknown -> Icon(
                    painter = painterResource(Res.drawable.mobile),
                    contentDescription = "Unknown device",
                    modifier = Modifier.size(glyphSize),
                    tint = glyphColor,
                )
            }
        }

        // Status dot overlay — bottom-end corner
        if (status != null) {
            Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                StatusDot(
                    status = status,
                    dotSize = dotSize,
                    outlineWidth = dotOutline,
                    outlineColor = fillColor,
                )
            }
        }
    }
}
