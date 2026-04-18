package com.carlom.klardrop.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Small inline indicator for an owned device.
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
        imageVector = Icons.Default.Person,
        contentDescription = "Your device",
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onPrimaryContainer
      )
      Text(
        text = "Your device",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Medium
      )
    }
  }
}

/**
 * Action button to add a nearby device as one of "your devices".
 * Shown only on devices that have completed handshake but aren't owned yet.
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
    Box(modifier = modifier) {
      IconButton(
        onClick = onRemoveTrust,
        enabled = !isLoading
      ) {
        Icon(
          imageVector = Icons.Default.Person,
          contentDescription = "Remove from your devices",
          tint = MaterialTheme.colorScheme.primary
        )
      }
    }
  } else {
    Box(modifier = modifier) {
      if (isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.dp
        )
      } else {
        IconButton(onClick = onAddToTrusted) {
          Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = "Add to your devices",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

/**
 * Small badge overlaid on a device avatar to mark it as one of your own.
 */
@Composable
fun TrustBadge(
  modifier: Modifier = Modifier
) {
  Box(
    modifier = modifier
      .size(18.dp)
      .clip(CircleShape)
      .background(MaterialTheme.colorScheme.primary),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = Icons.Default.Person,
      contentDescription = "Your device",
      modifier = Modifier.size(11.dp),
      tint = MaterialTheme.colorScheme.onPrimary
    )
  }
}

/**
 * Below-the-name status text for a device card.
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
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(12.dp),
          strokeWidth = 1.5.dp
        )
        Text(
          text = "Linking…",
          style = MaterialTheme.typography.labelSmall,
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

    else -> { /* no indicator for nearby/untrusted devices */
    }
  }
}
