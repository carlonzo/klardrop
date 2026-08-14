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

enum class KdRowVariant {
    /**
     * Flush 56 dp list row on a transparent ground — sidebar, sheets, pickers,
     * where rows sit inside an already-elevated surface.
     */
    List,

    /**
     * Free-standing 68 dp card on bg/0 — the discovery dashboard. Each row is
     * its own filled, rounded surface with air between it and its neighbours.
     */
    Card,
}

/**
 * Device list row — 56 dp flush ([KdRowVariant.List]) or 68 dp filled card
 * ([KdRowVariant.Card]).
 *
 * @param name          primary device name — single line, ellipsis
 * @param subText       optional caption below the name
 * @param kind          platform glyph for the leading avatar
 * @param avatarStyle   Tinted or Neutral
 * @param rowState      drives background and text color
 * @param status        reachability dot shown on avatar; null = no dot
 * @param variant       flush list row or free-standing card
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
    variant: KdRowVariant = KdRowVariant.List,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val isCard = variant == KdRowVariant.Card

    val rowBg: Color = when (rowState) {
        KdRowState.Active    -> colors.trustBg
        KdRowState.Hover     -> colors.bg3
        KdRowState.Pairing   -> colors.bg3
        // A card is a surface in its own right, so it stays filled at rest; a
        // list row is flush and only paints when something is happening.
        else                 -> if (isCard) colors.bg1 else Color.Transparent
    }

    // Offline reads as dimmed, not as an error. The red status dot on the
    // avatar already carries that bit — painting the whole row red says it a
    // second time, and "this device is asleep" is not a failure worth shouting.
    val nameColor: Color = when (rowState) {
        KdRowState.Active      -> colors.trust
        KdRowState.Unreachable -> colors.text2
        else                   -> colors.text
    }

    val subColor: Color = when (rowState) {
        KdRowState.Active      -> colors.trust
        KdRowState.Pairing     -> colors.warn
        KdRowState.Unreachable -> colors.text3
        else                   -> colors.text2
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (isCard) 68.dp else 56.dp)
            .clip(radii.shapeLg)
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = if (isCard) spacing.s4 else spacing.s3),
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
                size = if (isCard) 40.dp else 36.dp,
            )

            androidx.compose.foundation.layout.Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = name,
                    style = (if (isCard) typography.headline else typography.body).copy(
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
