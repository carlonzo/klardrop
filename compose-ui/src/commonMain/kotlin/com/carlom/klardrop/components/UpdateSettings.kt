package com.carlom.klardrop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.update.InstallChannel
import com.carlom.klardrop.common.update.InstallProgress
import com.carlom.klardrop.common.update.UpdateAction
import com.carlom.klardrop.common.update.UpdateStatus
import com.carlom.klardrop.theme.KdTheme
import kotlinx.coroutines.delay

/**
 * The Updates section of the settings sheet — the place a user goes to *ask*
 * "am I on the latest?", as opposed to the banner, which only appears when the
 * answer is already no.
 *
 * It always states the running version and how this copy was installed, then the
 * live check result: checking, up to date, failed, or a new version with the
 * upgrade path for this install channel. Renders nothing on platforms where the
 * store owns updates (Android/iOS) — see [visible].
 *
 * @param visible false on platforms without in-app updates; the section collapses away.
 * @param currentVersion this build's version.
 * @param releaseChannel "stable" or "nightly" — only shown when it isn't stable.
 * @param onCheck manual re-check.
 * @param onAction performs the channel's [UpdateAction]; returns true if it copied a command.
 * @param onRestart applies a staged self-update (swap + relaunch).
 * @param onOpenUrl opens a URL (the release notes link).
 */
@Composable
fun UpdateSettingsSection(
  visible: Boolean,
  currentVersion: String,
  releaseChannel: String,
  status: UpdateStatus,
  installProgress: InstallProgress,
  onCheck: () -> Unit,
  onAction: (UpdateAction) -> Boolean,
  onRestart: () -> Unit,
  onOpenUrl: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!visible) return

  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val spacing = KdTheme.spacing

  val available = status as? UpdateStatus.Available
  var copied by remember(available?.version) { mutableStateOf(false) }

  // The "Copied!" confirmation is transient — put the command back on the button
  // so a second copy is possible without reopening the sheet.
  LaunchedEffect(copied) {
    if (copied) {
      delay(2_000)
      copied = false
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    Text(text = "Updates", style = typography.headline.copy(color = colors.text))
    Spacer(Modifier.height(spacing.s2))

    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "Version $currentVersion" +
            if (releaseChannel != "stable") " · $releaseChannel" else "",
          style = typography.body.copy(color = colors.text),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = statusLine(status, installProgress),
          style = typography.caption.copy(color = statusColor(status, installProgress)),
        )
      }

      Spacer(Modifier.width(spacing.s3))

      // "Check for updates" stays available in every state except mid-check, so a
      // failed check is one tap from a retry.
      PillButton(
        label = if (status is UpdateStatus.Checking) "Checking…" else "Check for updates",
        enabled = status !is UpdateStatus.Checking,
        onClick = onCheck,
      )
    }

    if (available != null) {
      Spacer(Modifier.height(spacing.s3))
      UpdatePath(
        available = available,
        installProgress = installProgress,
        copied = copied,
        onAction = { if (onAction(it)) copied = true },
        onRestart = onRestart,
        onOpenUrl = onOpenUrl,
      )
    }
  }
}

/** How to actually get the available update, given the channel and self-update state. */
@Composable
private fun UpdatePath(
  available: UpdateStatus.Available,
  installProgress: InstallProgress,
  copied: Boolean,
  onAction: (UpdateAction) -> Unit,
  onRestart: () -> Unit,
  onOpenUrl: (String) -> Unit,
) {
  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val spacing = KdTheme.spacing
  val radii = KdTheme.radii

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(radii.shapeMd)
      .background(colors.accent.copy(alpha = 0.10f))
      .padding(spacing.s3),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    Text(
      text = "Klardrop ${available.version} is available",
      style = typography.body.copy(color = colors.text),
    )
    Text(
      text = "Installed via ${available.channel.displayName}.",
      style = typography.caption.copy(color = colors.text2),
    )

    when (installProgress) {
      is InstallProgress.Downloading -> {
        val fraction = installProgress.fraction
        Text(
          text = fraction?.let { "Downloading — ${(it * 100).toInt()}%" } ?: "Preparing update…",
          style = typography.caption.copy(color = colors.text2),
        )
        // An indeterminate bar while extracting: the download fraction is gone but
        // work is still happening, and a bar frozen at 100% reads as "stuck".
        if (fraction != null) {
          LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(radii.shapePill),
            color = colors.accent,
            trackColor = colors.accent.copy(alpha = 0.20f),
          )
        } else {
          LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(radii.shapePill),
            color = colors.accent,
            trackColor = colors.accent.copy(alpha = 0.20f),
          )
        }
      }

      is InstallProgress.Ready -> {
        Text(
          text = "Downloaded and verified. Restart to finish installing.",
          style = typography.caption.copy(color = colors.text2),
        )
        PillButton(label = "Restart now", onClick = onRestart)
      }

      else -> {
        // Idle (this channel can't self-install) or Failed (it tried and couldn't):
        // either way the channel's own upgrade path is what's left.
        if (installProgress is InstallProgress.Failed) {
          Text(
            text = "Automatic update failed: ${installProgress.message}",
            style = typography.caption.copy(color = colors.err),
          )
        }
        ManualUpdateAction(
          action = available.action,
          channel = available.channel,
          copied = copied,
          onAction = onAction,
        )
      }
    }

    available.notesUrl?.let { url ->
      Text(
        text = "Release notes",
        style = typography.caption.copy(color = colors.accent),
        modifier = Modifier.clickable { onOpenUrl(url) },
      )
    }
  }
}

/** The copy-a-command / open-a-download affordance for channels we can't update in place. */
@Composable
private fun ManualUpdateAction(
  action: UpdateAction,
  channel: InstallChannel,
  copied: Boolean,
  onAction: (UpdateAction) -> Unit,
) {
  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val spacing = KdTheme.spacing
  val radii = KdTheme.radii

  when (action) {
    is UpdateAction.RunCommand -> {
      Text(
        text = "${channel.displayName} manages this install — run:",
        style = typography.caption.copy(color = colors.text2),
      )
      // The command itself is selectable-looking but tap-to-copy: a terminal command
      // in a sheet is useless unless it reaches the clipboard in one gesture.
      Text(
        text = action.command,
        style = typography.mono.copy(color = colors.text),
        modifier = Modifier
          .fillMaxWidth()
          .clip(radii.shapeSm)
          .background(colors.bg2)
          .clickable { onAction(action) }
          .padding(horizontal = spacing.s2, vertical = spacing.s2),
      )
      PillButton(
        label = if (copied) "Copied!" else "Copy command",
        onClick = { onAction(action) },
      )
    }

    is UpdateAction.OpenUrl -> PillButton(
      label = "Download",
      onClick = { onAction(action) },
    )
  }
}

/** Small accent pill, the same shape the update banner's primary action uses. */
@Composable
private fun PillButton(
  label: String,
  onClick: () -> Unit,
  enabled: Boolean = true,
) {
  val colors = KdTheme.colors
  val typography = KdTheme.typography
  val spacing = KdTheme.spacing
  val radii = KdTheme.radii

  Box(
    modifier = Modifier
      .clip(radii.shapeMd)
      .background(colors.accent.copy(alpha = if (enabled) 0.16f else 0.08f))
      .let { if (enabled) it.clickable(onClick = onClick) else it }
      .padding(horizontal = spacing.s3, vertical = spacing.s1),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = typography.caption.copy(
        color = if (enabled) colors.accent else colors.text3,
      ),
      maxLines = 1,
    )
  }
}

/** One-line summary of where the update check stands. */
private fun statusLine(status: UpdateStatus, installProgress: InstallProgress): String = when {
  installProgress is InstallProgress.Ready -> "Update ready — restart to apply"
  status is UpdateStatus.Checking -> "Checking for updates…"
  status is UpdateStatus.UpToDate -> "You're on the latest version"
  status is UpdateStatus.Failed -> status.message
  status is UpdateStatus.Available -> "Update available"
  else -> "Not checked yet"
}

@Composable
private fun statusColor(status: UpdateStatus, installProgress: InstallProgress): Color {
  val colors = KdTheme.colors
  return when {
    installProgress is InstallProgress.Ready -> colors.accent
    status is UpdateStatus.Available -> colors.accent
    status is UpdateStatus.Failed -> colors.warn
    else -> colors.text2
  }
}
