package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C05 · SectionHead
// ---------------------------------------------------------------------------

/**
 * Overline label + zero-padded mono count + optional trailing slot.
 *
 * @param label         section name (rendered as overline — all-caps, 0.10em spacing)
 * @param count         item count; null hides the count
 * @param trailing      optional composable at the trailing edge (e.g. "Edit" link)
 * @param modifier      applied to the root Row
 */
@Composable
fun SectionHead(
    label: String,
    count: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = spacing.s5, end = spacing.s5, top = spacing.s5, bottom = spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        Text(
            text = label.uppercase(),
            style = typography.overline.copy(color = colors.text3),
            textAlign = TextAlign.Start,
        )

        if (count != null) {
            // Zero-padded to at least 2 digits
            Text(
                text = count.toString().padStart(2, '0'),
                style = typography.mono.copy(color = colors.text3),
            )
        }

        Spacer(Modifier.weight(1f))

        if (trailing != null) {
            trailing()
        }
    }
}
