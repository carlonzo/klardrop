package com.carlom.klardrop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carlom.klardrop.common.trust.model.TrustStatus
import com.carlom.klardrop.protos.trust.TrustLevel

/**
 * Trust status indicator badge to show on device cards
 */
@Composable
fun TrustStatusBadge(
    trustStatus: TrustStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    val (backgroundColor, contentColor, icon, label) = when (trustStatus) {
        TrustStatus.TRUSTED -> TrustStatusColors(
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            content = MaterialTheme.colorScheme.primary,
            icon = Icons.Default.Check,
            label = "Trusted"
        )
        TrustStatus.UNTRUSTED -> TrustStatusColors(
            background = MaterialTheme.colorScheme.surface,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Default.Lock,
            label = "Untrusted"
        )
        TrustStatus.PENDING_TRUST -> TrustStatusColors(
            background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f),
            content = MaterialTheme.colorScheme.secondary,
            icon = Icons.Default.Warning,
            label = "Pending"
        )
        TrustStatus.TRUST_EXPIRED -> TrustStatusColors(
            background = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
            content = MaterialTheme.colorScheme.error,
            icon = Icons.Default.Close,
            label = "Expired"
        )
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = contentColor
            )
            if (showLabel) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = contentColor
                )
            }
        }
    }
}

/**
 * Small trust indicator dot for compact displays
 */
@Composable
fun TrustIndicatorDot(
    trustStatus: TrustStatus,
    modifier: Modifier = Modifier
) {
    val color = when (trustStatus) {
        TrustStatus.TRUSTED -> MaterialTheme.colorScheme.primary
        TrustStatus.UNTRUSTED -> MaterialTheme.colorScheme.onSurfaceVariant
        TrustStatus.PENDING_TRUST -> MaterialTheme.colorScheme.secondary
        TrustStatus.TRUST_EXPIRED -> MaterialTheme.colorScheme.error
    }

    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * Trust level indicator showing the level of trust (FULL, LIMITED, etc.)
 */
@Composable
fun TrustLevelIndicator(
    trustLevel: TrustLevel?,
    modifier: Modifier = Modifier
) {
    trustLevel?.let { level ->
        val (text, color) = when (level) {
            TrustLevel.TRUST_LEVEL_FULL -> "Full" to MaterialTheme.colorScheme.primary
            TrustLevel.TRUST_LEVEL_LIMITED -> "Limited" to MaterialTheme.colorScheme.secondary
            TrustLevel.TRUST_LEVEL_REVOKED -> "Revoked" to MaterialTheme.colorScheme.error
            else -> return@let
        }

        Text(
            text = text,
            modifier = modifier,
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Permission indicator icons
 */
@Composable
fun PermissionIndicators(
    hasClipboardSync: Boolean,
    hasFileSend: Boolean,
    hasFileReceive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        if (hasClipboardSync) {
            Icon(
                imageVector = Icons.Default.Check, // Replace with clipboard icon when available
                contentDescription = "Clipboard Sync",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        if (!hasFileSend || !hasFileReceive) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Limited Permissions",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

/**
 * Trust group membership badge
 */
@Composable
fun TrustGroupBadge(
    isMember: Boolean,
    modifier: Modifier = Modifier
) {
    if (isMember) {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Group",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Combined trust indicator for device cards
 */
@Composable
fun DeviceTrustIndicator(
    deviceUi: DeviceUi,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false
) {
    if (showDetails) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrustStatusBadge(
                trustStatus = deviceUi.trustStatus,
                showLabel = true
            )
            
            if (deviceUi.isTrustGroupMember) {
                Spacer(modifier = Modifier.width(4.dp))
                TrustGroupBadge(isMember = true)
            }
            
            deviceUi.trustLevel?.let { level ->
                if (level != TrustLevel.TRUST_LEVEL_FULL) {
                    Spacer(modifier = Modifier.width(4.dp))
                    TrustLevelIndicator(trustLevel = level)
                }
            }
            
            if (deviceUi.hasClipboardSyncPermission || 
                !deviceUi.hasFileSendPermission || 
                !deviceUi.hasFileReceivePermission) {
                Spacer(modifier = Modifier.width(4.dp))
                PermissionIndicators(
                    hasClipboardSync = deviceUi.hasClipboardSyncPermission,
                    hasFileSend = deviceUi.hasFileSendPermission,
                    hasFileReceive = deviceUi.hasFileReceivePermission
                )
            }
        }
    } else {
        // Compact view - just show trust status badge
        TrustStatusBadge(
            trustStatus = deviceUi.trustStatus,
            modifier = modifier,
            showLabel = false
        )
    }
}

private data class TrustStatusColors(
    val background: Color,
    val content: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String
)