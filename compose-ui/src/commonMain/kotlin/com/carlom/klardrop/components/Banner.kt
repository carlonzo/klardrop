package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C08 · Banner
// ---------------------------------------------------------------------------

enum class KdBannerTone { Ok, Warn, Err }

/**
 * Inline status banner — ok / warn / err tones.
 *
 * @param tone          color tone of the banner
 * @param title         primary banner text
 * @param body          optional supporting text below the title
 * @param trailing      optional ghost/primary button at the trailing edge
 * @param modifier      applied to the root Row
 */
@Composable
fun Banner(
    tone: KdBannerTone,
    title: String,
    body: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val toneColor = when (tone) {
        KdBannerTone.Ok   -> colors.ok
        KdBannerTone.Warn -> colors.warn
        KdBannerTone.Err  -> colors.err
    }

    val bgColor = toneColor.copy(alpha = 0.10f)

    val icon = when (tone) {
        KdBannerTone.Ok   -> Icons.Default.CheckCircle
        KdBannerTone.Warn -> Icons.Default.Warning
        KdBannerTone.Err  -> Icons.Default.Error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(radii.shapeMd)
            .background(bgColor)
            .padding(horizontal = spacing.s3, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 28 dp icon tile
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(colors.text.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = toneColor,
            )
        }

        Spacer(Modifier.width(spacing.s3))

        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.body.copy(color = colors.text),
            )
            if (body != null) {
                Text(
                    text = body,
                    style = typography.caption.copy(color = colors.text2),
                )
            }
        }

        if (trailing != null) {
            Spacer(Modifier.width(spacing.s2))
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}
