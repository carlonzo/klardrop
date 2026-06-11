package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C15 · DateChip
// ---------------------------------------------------------------------------

/**
 * Centered date pill that separates chat thread days.
 *
 * @param label     date string (e.g. "Today", "Monday, May 5")
 * @param modifier  applied to the Box
 */
@Composable
fun DateChip(
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = radii.shapePill,
            color = colors.bg1,
        ) {
            Text(
                text = label,
                style = typography.overline.copy(color = colors.text3),
                modifier = Modifier.padding(
                    horizontal = spacing.s3,
                    vertical = spacing.s1,
                ),
            )
        }
    }
}
