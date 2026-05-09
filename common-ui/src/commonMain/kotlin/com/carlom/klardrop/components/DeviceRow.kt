package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C02 · DeviceRow
// ---------------------------------------------------------------------------

enum class KdRowState {
    Idle,
    Hover,
    Active,
    Pairing,
    Unreachable,
    PairPrompt,
}

/**
 * 56 dp height device list row.
 *
 * @param name          primary device name — single line, ellipsis
 * @param subText       optional caption below the name
 * @param kind          platform glyph for the leading avatar
 * @param avatarStyle   Tinted or Neutral
 * @param rowState      drives background and text color
 * @param status        reachability dot shown on avatar; null = no dot
 * @param trailing      optional composable slot at the trailing edge
 * @param onClick       row tap callback
 * @param modifier      applied to root Row
 */
@Composable
fun DeviceRow(
    name: String,
    subText: String? = null,
    kind: KdDeviceKind = KdDeviceKind.Unknown,
    avatarStyle: KdAvatarStyle = KdAvatarStyle.Neutral,
    rowState: KdRowState = KdRowState.Idle,
    status: KdStatus? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val rowBg: Color = when (rowState) {
        KdRowState.Active    -> colors.trustBg
        KdRowState.Hover     -> colors.bg3
        KdRowState.Pairing   -> colors.bg3
        else                 -> Color.Transparent
    }

    val nameColor: Color = when (rowState) {
        KdRowState.Active      -> colors.trust
        KdRowState.Unreachable -> colors.err
        else                   -> colors.text
    }

    val subColor: Color = when (rowState) {
        KdRowState.Active      -> colors.trust
        KdRowState.Pairing     -> colors.warn
        KdRowState.Unreachable -> colors.err
        else                   -> colors.text2
    }

    val shape = if (rowState == KdRowState.Active || rowState == KdRowState.Hover) {
        radii.shapeLg
    } else {
        radii.shapeLg
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        ) {
            DeviceAvatar(
                kind = kind,
                style = avatarStyle,
                status = status,
                size = 36.dp,
            )

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = name,
                    style = typography.body.copy(
                        color = nameColor,
                        fontWeight = if (rowState == KdRowState.Active) FontWeight(600)
                        else FontWeight(500),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subText,
                        style = typography.caption.copy(color = subColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailing != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailing,
                )
            }
        }
    }
}
