package com.carlom.klardrop.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C13 · PermissionsChecklist
// ---------------------------------------------------------------------------

data class KdPermissionItem(
    val id: String,
    val title: String,
    val caption: String,
    val isGranted: Boolean,
)

/**
 * Warn-toned permissions checklist panel.
 * Collapses with 220 ms slide animation when all permissions are granted.
 *
 * @param items     list of permission items to display
 * @param onAllow   called when user taps "Allow ›" for a specific item
 * @param modifier  applied to the outer Column
 */
@Composable
fun PermissionsChecklist(
    items: List<KdPermissionItem>,
    onAllow: (KdPermissionItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing
    val motion = KdTheme.motion

    val allGranted = items.all { it.isGranted }

    AnimatedVisibility(
        visible = !allGranted,
        enter = expandVertically(),
        exit = shrinkVertically(
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = motion.dBase,
            ),
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(radii.shapeMd)
                .background(colors.warn.copy(alpha = 0.10f))
                .padding(spacing.s3),
            verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            Text(
                text = "Permissions needed",
                style = typography.overline.copy(color = colors.warn),
            )

            items.filter { !it.isGranted }.forEach { item ->
                PermissionRow(
                    item = item,
                    onAllow = { onAllow(item) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    item: KdPermissionItem,
    onAllow: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        Icon(
            imageVector = if (item.isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (item.isGranted) colors.ok else colors.warn,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = typography.body.copy(color = colors.text),
            )
            Text(
                text = item.caption,
                style = typography.caption.copy(color = colors.text2),
            )
        }

        if (!item.isGranted) {
            Text(
                text = "Allow ›",
                style = typography.body.copy(color = colors.accent),
                modifier = Modifier
                    .clickable(onClick = onAllow)
                    .padding(horizontal = spacing.s1),
            )
        }
    }
}
