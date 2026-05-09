package com.carlom.klardrop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C09 · MessageInput
// ---------------------------------------------------------------------------

/**
 * Pill message input bar fixed to the bottom safe area.
 *
 * @param value             current text value
 * @param onValueChange     text change callback
 * @param onSend            send button tap
 * @param onAttach          clip/attach button tap
 * @param enabled           false = 45% opacity, placeholder "Input disabled"
 * @param desktopVariant    true = dashed border + helper text drop hint
 * @param modifier          applied to the root Row
 */
@Composable
fun MessageInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit = {},
    enabled: Boolean = true,
    desktopVariant: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val alpha = if (enabled) 1f else 0.45f
    val inputHeight = if (desktopVariant) 38.dp else 40.dp

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading attach button
            IconButton(
                onClick = onAttach,
                enabled = enabled,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = colors.text2.copy(alpha = alpha),
                )
            }

            Spacer(Modifier.width(spacing.s1))

            // Pill text field
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(inputHeight),
                shape = radii.shapePill,
                color = colors.bg2,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (enabled && !desktopVariant) colors.border
                    else colors.border.copy(alpha = if (desktopVariant) 0.7f else alpha),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s3),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = if (!enabled) "Input disabled" else "Message",
                            style = typography.body.copy(
                                color = colors.text3.copy(alpha = alpha),
                            ),
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { if (enabled) onValueChange(it) },
                        enabled = enabled,
                        textStyle = typography.body.copy(color = colors.text.copy(alpha = alpha)),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            }

            Spacer(Modifier.width(spacing.s2))

            // Trailing 40 dp accent send button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(radii.shapePill)
                    .background(
                        if (enabled) colors.accent else colors.accent.copy(alpha = alpha)
                    )
                    .then(
                        if (enabled) Modifier else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = enabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        tint = colors.textInv,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // Desktop helper text
        if (desktopVariant) {
            Text(
                text = "or drop files anywhere on this pane",
                style = typography.caption.copy(color = colors.text3),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = spacing.s1),
            )
        }
    }
}
