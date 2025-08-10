package com.carlom.klardrop.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Trust status indicator for devices in discovery lists.
 */
@Composable
fun TrustStatusIndicator(
    isTrusted: Boolean,
    modifier: Modifier = Modifier
) {
    if (isTrusted) {
        Row(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = "Trusted Device",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Trusted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Action button for trust management in device lists.
 */
@Composable
fun TrustActionButton(
    isTrusted: Boolean,
    isLoading: Boolean = false,
    onAddToTrusted: () -> Unit,
    onRemoveTrust: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isTrusted) {
        // Show trusted indicator with option to remove
        Box(modifier = modifier) {
            IconButton(
                onClick = onRemoveTrust,
                enabled = !isLoading
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Remove Trust",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    } else {
        // Show add to trusted button
        Box(modifier = modifier) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = onAddToTrusted) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = "Add to Trusted",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Trust badge that can be shown over device icons.
 */
@Composable
fun TrustBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = "Trusted",
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}

/**
 * Comprehensive trust status display with multiple states.
 */
@Composable
fun DeviceTrustStatus(
    isTrusted: Boolean,
    isPairing: Boolean = false,
    modifier: Modifier = Modifier
) {
    when {
        isPairing -> {
            Row(
                modifier = modifier,
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Pairing...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        isTrusted -> {
            TrustStatusIndicator(
                isTrusted = true,
                modifier = modifier
            )
        }
        
        else -> {
            // No indicator for untrusted devices
        }
    }
}