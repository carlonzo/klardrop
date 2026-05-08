package com.carlom.klardrop.trust

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.theme.KdTheme

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
          color = KdTheme.colors.trustBg,
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
        tint = KdTheme.colors.trust
      )
      Text(
        text = "Your device",
        style = KdTheme.typography.overline,
        color = KdTheme.colors.trust,
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
          tint = KdTheme.colors.trust
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
            tint = KdTheme.colors.text2
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
      .background(KdTheme.colors.trust),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = Icons.Default.Person,
      contentDescription = "Your device",
      modifier = Modifier.size(11.dp),
      tint = KdTheme.colors.trustFg
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
          style = KdTheme.typography.caption,
          color = KdTheme.colors.text2
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
