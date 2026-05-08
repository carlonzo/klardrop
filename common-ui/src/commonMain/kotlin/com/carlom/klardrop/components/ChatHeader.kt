package com.carlom.klardrop.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C14 · ChatHeader
// ---------------------------------------------------------------------------

/**
 * 56 dp app bar for the chat screen.
 *
 * @param deviceName        device name — single line, ellipsis
 * @param subText           reachability caption (e.g. "Reachable", "Offline")
 * @param kind              avatar platform glyph
 * @param avatarStyle       Tinted (trusted) or Neutral
 * @param status            reachability dot on avatar
 * @param isReachable       drives sub text color: trust (true) or err (false)
 * @param toolbarVariant    true = desktop toolbar; hides back button, shifts padding
 * @param onBack            back button tap (ignored in toolbarVariant)
 * @param onOverflow        overflow icon tap
 * @param modifier          applied to root Column
 */
@Composable
fun ChatHeader(
    deviceName: String,
    subText: String = "",
    kind: KdDeviceKind = KdDeviceKind.Unknown,
    avatarStyle: KdAvatarStyle = KdAvatarStyle.Neutral,
    status: KdStatus? = null,
    isReachable: Boolean = true,
    toolbarVariant: Boolean = false,
    onBack: () -> Unit = {},
    onOverflow: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val spacing = KdTheme.spacing

    val subColor = if (isReachable) colors.trust else colors.err
    val horizontalPad = if (toolbarVariant) spacing.s4 else spacing.s2

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = horizontalPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
            // Back button — mobile only
            if (!toolbarVariant) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = colors.text,
                    )
                }
            }

            // 32 dp avatar
            DeviceAvatar(
                kind = kind,
                style = avatarStyle,
                status = status,
                size = 32.dp,
            )

            Spacer(Modifier.width(spacing.s2))

            // Name + sub
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceName,
                    style = typography.body.copy(
                        color = colors.text,
                        fontWeight = FontWeight(600),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subText.isNotBlank()) {
                    Text(
                        text = subText,
                        style = typography.caption.copy(color = subColor),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Overflow icon
            IconButton(
                onClick = onOverflow,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = colors.text2,
                )
            }
        }

        // 1 px bottom divider
        HorizontalDivider(
            thickness = 1.dp,
            color = colors.divider,
        )
    }
}
