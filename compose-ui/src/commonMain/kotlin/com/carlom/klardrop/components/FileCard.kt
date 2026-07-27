package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

// ---------------------------------------------------------------------------
// C07 · FileCard
// ---------------------------------------------------------------------------

sealed class KdFileState {
    /** [progress] null = transfer is live but has no percentage yet → indeterminate bar. */
    data class Sending(val progress: Float?) : KdFileState()
    data class Receiving(val progress: Float?) : KdFileState()
    object Done : KdFileState()
    object Failed : KdFileState()
}

/**
 * File transfer card embedded inside a Bubble.
 *
 * @param fileName      the file name — mono, 1-line, ellipsis
 * @param fileSize      optional human-readable size string
 * @param state         Sending / Receiving / Done / Failed
 * @param onRetry       called when the user taps a Failed card
 * @param modifier      applied to the outermost Box
 */
@Composable
fun FileCard(
    fileName: String,
    fileSize: String? = null,
    state: KdFileState,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val isClickable = state is KdFileState.Failed

    Box(
        modifier = modifier
            .widthIn(min = 240.dp)
            .then(if (isClickable) Modifier.clickable(onClick = onRetry) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(vertical = spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 40 dp icon tile
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(radii.shapeSm)
                    .background(colors.bg3),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = colors.text2,
                )
            }

            Spacer(Modifier.width(spacing.s3))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = fileName,
                        style = typography.mono.copy(color = colors.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    // Trailing check for Done
                    if (state is KdFileState.Done) {
                        Spacer(Modifier.width(spacing.s2))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Done",
                            modifier = Modifier.size(16.dp),
                            tint = colors.ok,
                        )
                    }
                }

                if (fileSize != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = fileSize,
                        style = typography.caption.copy(color = colors.text3),
                    )
                }

                // Progress bar
                when (state) {
                    is KdFileState.Sending, is KdFileState.Receiving -> {
                        val progress = when (state) {
                            is KdFileState.Sending   -> state.progress
                            is KdFileState.Receiving -> state.progress
                        }
                        Spacer(Modifier.height(spacing.s1))
                        val barModifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                        // A null fraction means the transfer is live but hasn't got a
                        // percentage yet (connecting, waiting for the recipient to accept,
                        // opening the sink). Animate rather than pinning the bar at 0% —
                        // a motionless bar is indistinguishable from a stalled transfer.
                        if (progress == null) {
                            LinearProgressIndicator(
                                modifier = barModifier,
                                color = colors.accent,
                                trackColor = colors.text.copy(alpha = 0.08f),
                                strokeCap = StrokeCap.Round,
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = barModifier,
                                color = colors.accent,
                                trackColor = colors.text.copy(alpha = 0.08f),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                    }
                    is KdFileState.Failed -> {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Failed · Tap to retry",
                            style = typography.caption.copy(color = colors.err),
                        )
                    }
                    else -> {}
                }
            }
        }
    }
}
