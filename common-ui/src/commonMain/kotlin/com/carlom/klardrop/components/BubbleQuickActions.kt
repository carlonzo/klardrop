package com.carlom.klardrop.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// Bubble quick actions — a subtle row of icon buttons rendered *below* a chat
// bubble (outside the bubble background), aligned to the speaker side. Text
// bubbles get Copy (+ Expand when clipped); image bubbles get Open.
// ---------------------------------------------------------------------------

@Composable
fun BubbleQuickActions(
    direction: KdBubbleDirection,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val spacing = KdTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.s1, start = spacing.s1, end = spacing.s1),
        horizontalArrangement = Arrangement.spacedBy(
            spacing.s1,
            if (direction == KdBubbleDirection.Out) Alignment.End else Alignment.Start,
        ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    Box(
        modifier = modifier
            .size(28.dp)
            .clip(KdTheme.radii.shapePill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.text3,
            modifier = Modifier.size(16.dp),
        )
    }
}
