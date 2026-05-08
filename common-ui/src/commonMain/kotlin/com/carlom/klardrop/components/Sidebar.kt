package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C16 · Sidebar
// ---------------------------------------------------------------------------

/**
 * Left sidebar pane for desktop/tablet split view.
 *
 * @param width             sidebar width; use 300 dp (macOS) or 320 dp (iPad)
 * @param visibilityState   wired to VisibilityPill
 * @param onVisibilityTap   called when pill is tapped
 * @param yoursSection      composable content for the "Your devices" section
 * @param nearbySection     composable content for the "Nearby" section
 * @param footer            composable content pinned at the bottom (local device + settings)
 * @param modifier          applied to the root Box
 */
@Composable
fun Sidebar(
    width: Dp = 300.dp,
    visibilityState: KdVisibilityState = KdVisibilityState.Hidden,
    onVisibilityTap: () -> Unit = {},
    yoursSection: @Composable ColumnScope.() -> Unit = {},
    nearbySection: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    Row(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(width)
                .fillMaxHeight()
                .background(colors.bgSidebar),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
            ) {
                // Scrollable content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // Visibility pill
                    VisibilityPill(
                        state = visibilityState,
                        onTap = onVisibilityTap,
                        modifier = Modifier
                            .padding(
                                horizontal = spacing.s5,
                                vertical = spacing.s3,
                            ),
                    )

                    // Yours section
                    yoursSection()

                    Spacer(Modifier.height(spacing.s4))

                    // Nearby section
                    nearbySection()
                }

                // Footer — not scrollable
                HorizontalDivider(thickness = 1.dp, color = colors.divider)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(spacing.s3),
                    verticalArrangement = Arrangement.spacedBy(spacing.s1),
                ) {
                    footer()
                }
            }
        }

        // 1 px right border
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.border),
        )
    }
}
