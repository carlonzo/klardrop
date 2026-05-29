package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import com.carlom.klardrop.theme.KdTheme

/**
 * Dismissible "update available" banner shown at the top of the discovery
 * screen. Renders nothing unless [status] is [UpdateStatus.Available] (so it's
 * invisible on mobile and when up to date). For a package-manager install it
 * shows the exact upgrade command with a copy button; otherwise a Download
 * button that opens the right asset.
 *
 * @param onAction performs the action; returns true if it copied a command.
 */
@Composable
fun UpdateBanner(
  status: UpdateStatus,
  onAction: (UpdateAction) -> Boolean,
  modifier: Modifier = Modifier,
) {
  val available = status as? UpdateStatus.Available ?: return

  // Dismissal is per-version: a newer release re-shows the banner.
  var dismissedVersion by remember { mutableStateOf<String?>(null) }
  if (dismissedVersion == available.version) return

  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val radii = KdTheme.radii
  val spacing = KdTheme.spacing

  val action = available.action
  var copied by remember(available.version) { mutableStateOf(false) }

  val actionLabel = when (action) {
    is UpdateAction.RunCommand -> if (copied) "Copied!" else "Copy command"
    is UpdateAction.OpenUrl -> "Download"
  }
  val detail = when (action) {
    is UpdateAction.RunCommand -> action.command
    is UpdateAction.OpenUrl -> "A new version is ready to download."
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = spacing.s3, vertical = spacing.s2)
      .clip(radii.shapeMd)
      .background(colors.accent.copy(alpha = 0.10f))
      .padding(horizontal = spacing.s3, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(28.dp)
        .clip(CircleShape)
        .background(colors.text.copy(alpha = 0.04f)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Info,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = colors.accent,
      )
    }

    Spacer(Modifier.width(spacing.s3))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = "Update available — ${available.version}",
        style = typography.body.copy(color = colors.text),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = detail,
        style = typography.caption.copy(color = colors.text2),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    Spacer(Modifier.width(spacing.s2))

    // Primary action pill.
    Box(
      modifier = Modifier
        .clip(radii.shapeMd)
        .background(colors.accent.copy(alpha = 0.16f))
        .clickable {
          val didCopy = onAction(action)
          if (didCopy) copied = true
        }
        .padding(horizontal = spacing.s3, vertical = spacing.s1),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = actionLabel,
        style = typography.caption.copy(color = colors.accent),
        maxLines = 1,
      )
    }

    Spacer(Modifier.width(spacing.s1))

    // Dismiss.
    Box(
      modifier = Modifier
        .size(spacing.s6)
        .clip(CircleShape)
        .clickable { dismissedVersion = available.version },
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = "Dismiss",
        tint = colors.text3,
        modifier = Modifier.size(spacing.s4),
      )
    }
  }
}
