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
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import com.carlom.klardrop.theme.KdTheme

/**
 * Dismissible "update available" banner shown at the top of the discovery
 * screen. Renders nothing unless [status] is [UpdateStatus.Available] (so it's
 * invisible on mobile and when up to date).
 *
 * When the app can update itself ([installProgress] leaves [InstallProgress.Idle]),
 * it shows download progress and then a Restart button. Otherwise it falls back to
 * the channel's [UpdateAction]: a copyable upgrade command, or a Download button.
 *
 * @param onAction performs the action; returns true if it copied a command.
 * @param onRestart applies a staged update (swap + relaunch).
 */
@Composable
fun UpdateBanner(
  status: UpdateStatus,
  installProgress: InstallProgress,
  onAction: (UpdateAction) -> Boolean,
  onRestart: () -> Unit,
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

  // Self-update states take priority over the manual action; on failure we fall
  // back to the action (copy command / download).
  val fallbackLabel = when (action) {
    is UpdateAction.RunCommand -> if (copied) "Copied!" else "Copy command"
    is UpdateAction.OpenUrl -> "Download"
  }
  val fallbackDetail = when (action) {
    is UpdateAction.RunCommand -> action.command
    is UpdateAction.OpenUrl -> "A new version is ready to download."
  }

  val (detail, actionLabel, onClick) = when (installProgress) {
    is InstallProgress.Downloading -> {
      val pct = installProgress.fraction?.let { " ${(it * 100).toInt()}%" } ?: "…"
      Triple("Downloading update$pct", null, null)
    }

    is InstallProgress.Ready ->
      Triple("Update downloaded — restart to apply.", "Restart", onRestart)

    else -> Triple(
      fallbackDetail,
      fallbackLabel,
      { if (onAction(action)) copied = true },
    )
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

    // Primary action pill — hidden while a download is in flight (no action yet).
    if (actionLabel != null && onClick != null) {
      Box(
        modifier = Modifier
          .clip(radii.shapeMd)
          .background(colors.accent.copy(alpha = 0.16f))
          .clickable { onClick() }
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
    }

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
