package com.carlom.klardrop.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

/**
 * Slim banner card for in-app system notifications surfaced above incoming-transfer cards
 * in [IncomingBannerStack]. Shares the visual rhythm of [IncomingTransferCard] (rounded
 * surface, dual-action footer) so the stack reads coherently, but without the
 * sender-avatar / file-info chrome a transfer needs.
 */
@Composable
fun SystemNotificationCard(
    title: String,
    body: String,
    primaryAction: String,
    onPrimary: () -> Unit,
    secondaryAction: String,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KdTheme.colors
    val typography = KdTheme.typography
    val radii = KdTheme.radii
    val spacing = KdTheme.spacing

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = radii.shapeLg,
        color = colors.bg2,
        tonalElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(spacing.s4)) {
            Text(
                text = title,
                style = typography.body.copy(color = colors.text),
            )
            Text(
                text = body,
                style = typography.caption.copy(color = colors.text2),
            )

            Spacer(Modifier.height(spacing.s4))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    shape = radii.shapeMd,
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = colors.border,
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.text,
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ) {
                    Text(secondaryAction, style = typography.body)
                }

                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(40.dp),
                    shape = radii.shapeMd,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.textInv,
                    ),
                ) {
                    Text(primaryAction, style = typography.body)
                }
            }
        }
    }
}
