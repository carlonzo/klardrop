package com.carlom.klardrop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C16 · Sidebar — floating sheet
// ---------------------------------------------------------------------------

private val SidebarRadius = 12.dp

/**
 * Left sidebar pane for desktop / tablet split view.
 *
 * Renders as a floating "sheet" card (bg1, rounded 12 dp, 1 dp border). The
 * surrounding shell gutter is owned by the parent (WideLayout) so the right
 * pane and the sidebar share a consistent body padding.
 *
 * Layout inside the sheet: scrollable `yoursSection` + `nearbySection`,
 * a hairline divider, then a prominent local-device footer card that
 * surfaces the user's own device name and is tappable to rename it.
 *
 * @param width                 sheet width
 * @param yoursSection          composable content for the "Your devices" section (scrollable)
 * @param nearbySection         composable content for the "Nearby" section (scrollable)
 * @param localDeviceName       the user's own device name shown in the footer card
 * @param localDeviceSub        optional secondary line under the name (e.g. "This device")
 * @param onLocalDeviceClick    fired when the footer card is tapped — open the rename UI
 * @param modifier              applied to the Surface
 */
@Composable
fun Sidebar(
    width: Dp = 300.dp,
    yoursSection: @Composable ColumnScope.() -> Unit = {},
    nearbySection: @Composable ColumnScope.() -> Unit = {},
    localDeviceName: String,
    localDeviceSub: String? = null,
    onLocalDeviceClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val spacing = KdTheme.spacing

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        shape = RoundedCornerShape(SidebarRadius),
        color = colors.bg1,
        border = BorderStroke(1.dp, colors.border),
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(spacing.s2))
                yoursSection()
                Spacer(Modifier.height(spacing.s4))
                nearbySection()
                Spacer(Modifier.height(spacing.s2))
            }

            HorizontalDivider(thickness = 1.dp, color = colors.border)

            LocalDeviceFooter(
                name = localDeviceName,
                sub = localDeviceSub,
                onClick = onLocalDeviceClick,
            )
        }
    }
}

@Composable
private fun LocalDeviceFooter(
    name: String,
    sub: String?,
    onClick: () -> Unit,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = spacing.s3, vertical = spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    ) {
        DeviceAvatar(
            kind = KdDeviceKind.Mac,
            style = KdAvatarStyle.Tinted,
            status = KdStatus.Ok,
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name.ifBlank { "This device" },
                style = typography.body.copy(
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!sub.isNullOrBlank()) {
                Text(
                    text = sub,
                    style = typography.caption.copy(color = colors.text3),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Rename this device",
                tint = colors.text3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
