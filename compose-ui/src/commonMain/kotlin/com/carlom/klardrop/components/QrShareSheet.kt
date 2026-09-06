package com.carlom.klardrop.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.qrshare.QrDownloadProgress
import com.carlom.klardrop.common.qrshare.QrSharePayload
import com.carlom.klardrop.common.qrshare.QrShareSession
import com.carlom.klardrop.common.qrshare.QrShareState
import com.carlom.klardrop.theme.KdTheme
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

// ---------------------------------------------------------------------------
// C12 · QrShareSheet
// ---------------------------------------------------------------------------

const val QR_HELPER_TEXT_FILES =
    "Keep this screen open until you see sending progress. Then you can hide it — the download will finish. Each scan is one phone; the code changes after someone opens it. Both devices must be on the same Wi-Fi. If the other phone warns about the certificate, tap Proceed — that’s this device. Open in Safari if the camera preview blocks it. Some guest networks block this. Turn off mobile data if it won’t open."

const val QR_HELPER_TEXT_TEXT =
    "Keep this open until they scan; they can copy the text on their phone. Each scan is one phone; the code changes after someone opens it. Both devices must be on the same Wi-Fi. If the other phone warns about the certificate, tap Proceed — that’s this device. Open in Safari if the camera preview blocks it. Some guest networks block this."

/**
 * Bottom-sheet content for sharing via QR code over LAN HTTPS.
 *
 * @param state         current state of the QR share session
 * @param onDismiss     called when user dismisses or hides the sheet
 * @param modifier      applied to the root Column
 * @param onCancel      optional cancel callback (e.g. aborting the session)
 * @param isText        true when sharing text payload; false for files
 * @param payload       optional payload object (if provided, determines isText automatically)
 */
@Composable
fun QrShareSheet(
    state: QrShareState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    isText: Boolean = false,
    payload: QrSharePayload? = null,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    val isTextPayload = isText || (payload is QrSharePayload.Text)
    val helperText = if (isTextPayload) QR_HELPER_TEXT_TEXT else QR_HELPER_TEXT_FILES

    val currentUrl = when (state) {
        is QrShareState.QrVisible -> state.url
        is QrShareState.Serving -> if (state.qrStillVisible) state.url else null
        else -> null
    }

    val downloads = when (state) {
        is QrShareState.Serving -> state.downloads
        else -> emptyList()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = spacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Drag Handle
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(radii.shapePill)
                .background(colors.text3.copy(alpha = 0.40f)),
        )

        Spacer(Modifier.height(spacing.s4))

        // Scrollable content area
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing.s4),
        ) {
            // Header title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(spacing.s1),
                modifier = Modifier.padding(horizontal = spacing.s4),
            ) {
                Text(
                    text = "Share via QR",
                    style = typography.headline.copy(color = colors.text),
                    textAlign = TextAlign.Center,
                )

                if (state is QrShareState.QrVisible && state.payloadSummary.isNotBlank()) {
                    Text(
                        text = state.payloadSummary,
                        style = typography.caption.copy(color = colors.text2),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Main display depending on state
            when (state) {
                is QrShareState.Starting -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.s3),
                        modifier = Modifier.padding(vertical = spacing.s6),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(36.dp),
                            color = colors.accent,
                        )
                        Text(
                            text = "Starting QR share…",
                            style = typography.body.copy(color = colors.text2),
                        )
                    }
                }

                is QrShareState.Failed -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(spacing.s3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.s4, vertical = spacing.s4),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.err.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                tint = colors.err,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Text(
                            text = state.message,
                            style = typography.body.copy(color = colors.err),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                is QrShareState.Idle -> {
                    Text(
                        text = "Ready to share",
                        style = typography.body.copy(color = colors.text2),
                        modifier = Modifier.padding(vertical = spacing.s4),
                    )
                }

                is QrShareState.QrVisible,
                is QrShareState.Serving -> {
                    if (currentUrl != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(spacing.s3),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // QR image
                            QrCodeImage(
                                url = currentUrl,
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(radii.shapeMd),
                            )

                            // Monospace URL under QR for accessibility and copying
                            SelectionContainer {
                                Text(
                                    text = currentUrl,
                                    style = typography.mono.copy(color = colors.text2),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = spacing.s4)
                                        .semantics {
                                            contentDescription = currentUrl
                                        },
                                )
                            }
                        }
                    }
                }
            }

            // Live download progress
            if (downloads.isNotEmpty()) {
                DownloadsProgressSection(
                    downloads = downloads,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s4),
                )
            }

            // Helper text (not shown when failed)
            if (state !is QrShareState.Failed) {
                Text(
                    text = helperText,
                    style = typography.caption.copy(color = colors.text2),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.s5),
                )
            }
        }

        Spacer(Modifier.height(spacing.s4))

        // Bottom CTA actions
        val buttonLabel = when {
            state is QrShareState.Failed -> "Dismiss"
            state is QrShareState.Serving && (downloads.isNotEmpty() || !state.qrStillVisible) -> "Hide — keeps sending"
            else -> "Dismiss"
        }

        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = spacing.s4),
            shape = radii.shapeMd,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.textInv,
            ),
        ) {
            Text(
                text = buttonLabel,
                style = typography.body,
            )
        }

        if (onCancel != null && state !is QrShareState.Failed && state !is QrShareState.Idle) {
            Spacer(Modifier.height(spacing.s2))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.padding(horizontal = spacing.s4),
            ) {
                Text(
                    text = "Cancel transfer",
                    style = typography.caption.copy(color = colors.err),
                )
            }
        }

        Spacer(Modifier.height(spacing.s7))
    }
}

/**
 * Convenience overload that observes [QrShareSession.state].
 */
@Composable
fun QrShareSheet(
    session: QrShareSession,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    isText: Boolean = false,
    payload: QrSharePayload? = null,
) {
    val state by session.state.collectAsState()
    QrShareSheet(
        state = state,
        onDismiss = onDismiss,
        modifier = modifier,
        onCancel = onCancel ?: { session.cancel() },
        isText = isText,
        payload = payload,
    )
}

/**
 * Renders a QR code as dark modules on a light (white) square background.
 * Uses ErrorCorrectionLevel.MEDIUM and a quiet zone of 4 modules.
 * Always renders dark modules on light background even in dark mode.
 */
@Composable
fun QrCodeImage(
    url: String,
    modifier: Modifier = Modifier,
) {
    val qrMatrix = remember(url) {
        if (url.isBlank()) null
        else {
            try {
                QRCodeProcessor(url, ErrorCorrectionLevel.MEDIUM).encode()
            } catch (_: Throwable) {
                null
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.White)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (qrMatrix != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "QR Code for $url"
                    },
            ) {
                val quietZone = 4
                val moduleCount = qrMatrix.size
                val totalModules = moduleCount + quietZone * 2
                val moduleSize = size.minDimension / totalModules
                val leftOffset = (size.width - totalModules * moduleSize) / 2f
                val topOffset = (size.height - totalModules * moduleSize) / 2f

                // Always light square background
                drawRect(
                    color = Color.White,
                    topLeft = Offset(leftOffset, topOffset),
                    size = Size(totalModules * moduleSize, totalModules * moduleSize),
                )

                // Always dark modules
                val darkColor = Color.Black
                for (row in 0 until moduleCount) {
                    for (col in 0 until moduleCount) {
                        if (qrMatrix[row][col].dark) {
                            val x = leftOffset + (col + quietZone) * moduleSize
                            val nextX = leftOffset + (col + quietZone + 1) * moduleSize
                            val y = topOffset + (row + quietZone) * moduleSize
                            val nextY = topOffset + (row + quietZone + 1) * moduleSize
                            drawRect(
                                color = darkColor,
                                topLeft = Offset(x, y),
                                size = Size(nextX - x, nextY - y),
                            )
                        }
                    }
                }
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = KdTheme.colors.accent,
            )
        }
    }
}

@Composable
private fun DownloadsProgressSection(
    downloads: List<QrDownloadProgress>,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        downloads.forEach { download ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(radii.shapeMd)
                    .background(colors.bg2)
                    .padding(horizontal = spacing.s3, vertical = spacing.s2),
                verticalArrangement = Arrangement.spacedBy(spacing.s1),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = download.fileName,
                        style = typography.caption.copy(color = colors.text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "${download.percentage}%",
                        style = typography.mono.copy(color = colors.text),
                        modifier = Modifier.padding(start = spacing.s2),
                    )
                }

                LinearProgressIndicator(
                    progress = { download.percentage / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(radii.shapePill),
                    color = colors.accent,
                    trackColor = colors.bg3,
                )
            }
        }
    }
}
