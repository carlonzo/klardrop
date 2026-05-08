package com.carlom.klardrop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.theme.KdTheme
import com.carlom.klardrop.trust.DeviceTrustStatus
import com.carlom.klardrop.trust.TrustBadge
import com.klardrop.resources.Res
import com.klardrop.resources.laptop
import com.klardrop.resources.mobile
import org.jetbrains.compose.resources.painterResource

@Composable
fun DeviceDiscovery(
  deviceUi: DeviceUi, isLargeScreen: Boolean, onDeviceActionListener: OnDeviceActionListener
) {
  if (isLargeScreen) {
    DeviceLarge(deviceUi, onDeviceActionListener)
  } else {
    DeviceSmall(deviceUi, onDeviceActionListener)
  }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DeviceSmall(deviceUi: DeviceUi, onDeviceActionListener: OnDeviceActionListener) {

  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(20.dp))
      .combinedClickable(
        onClick = {
          log("Device clicked ${deviceUi.deviceName}")
          onDeviceActionListener.onDeviceClick(deviceUi)
        },
      )
      .padding(vertical = 14.dp, horizontal = 8.dp)
      .width(96.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {

    Box {
      CircleDevice(deviceUi)

      if (deviceUi.trustStatus == TrustStatus.Trusted) {
        TrustBadge(
          modifier = Modifier.align(Alignment.TopEnd)
        )
      }

      if (deviceUi.hasUnreadMessages) {
        UnreadDot(
          modifier = Modifier.align(Alignment.TopStart)
        )
      }

      ReachabilityDot(
        reachability = deviceUi.reachability,
        modifier = Modifier.align(Alignment.BottomEnd)
      )
    }

    Text(
      text = deviceUi.deviceName,
      style = KdTheme.typography.body,
      color = KdTheme.colors.text,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center
    )

    DeviceTrustStatus(
      isTrusted = deviceUi.trustStatus == TrustStatus.Trusted,
      isPairing = deviceUi.trustStatus == TrustStatus.Pairing
    )
  }

}

@Composable
internal fun DeviceLarge(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
) {

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .clip(shape = RoundedCornerShape(20.dp))
      .clickable { onDeviceActionListener.onDeviceClick(deviceUi) }
      .deviceAdditions(deviceUi, onDeviceActionListener)
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {

    Row(verticalAlignment = Alignment.CenterVertically) {

      Box {
        CircleDevice(deviceUi)

        if (deviceUi.trustStatus == TrustStatus.Trusted) {
          TrustBadge(
            modifier = Modifier.align(Alignment.TopEnd)
          )
        }

        if (deviceUi.hasUnreadMessages) {
          UnreadDot(
            modifier = Modifier.align(Alignment.TopStart)
          )
        }

        ReachabilityDot(
          reachability = deviceUi.reachability,
          modifier = Modifier.align(Alignment.BottomEnd)
        )
      }

      Spacer(modifier = Modifier.size(16.dp))

      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = deviceUi.deviceName,
          style = KdTheme.typography.headline,
          color = KdTheme.colors.text,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )

        DeviceTrustStatus(
          isTrusted = deviceUi.trustStatus == TrustStatus.Trusted,
          isPairing = deviceUi.trustStatus == TrustStatus.Pairing
        )
      }
    }
  }
}

@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .size(12.dp)
      .background(KdTheme.colors.bg1, CircleShape)
      .padding(2.dp)
      .background(KdTheme.colors.err, CircleShape)
  )
}

@Composable
private fun ReachabilityDot(reachability: Reachability, modifier: Modifier = Modifier) {
  // Only render an explicit dot for the two terminal states. Unknown / Probing
  // happen continuously as visibleDevices and the prober update each other and
  // would flicker the indicator on every churn — silence is the right cue
  // there ("we don't know yet, don't draw conclusions").
  val color = when (reachability) {
    Reachability.Reachable -> KdTheme.colors.ok
    Reachability.Unreachable -> KdTheme.colors.err
    Reachability.Probing,
    Reachability.Unknown -> return
  }
  Box(
    modifier = modifier
      .size(12.dp)
      .background(KdTheme.colors.bg1, CircleShape)
      .padding(2.dp)
      .background(color, CircleShape)
  )
}

@Composable
private fun CircleDevice(deviceUi: DeviceUi) {
  val isSending = deviceUi.activityState is ActivityState.Sending
  val targetCircleSize = if (isSending) 52.dp else 56.dp

  val circleSize by animateDpAsState(targetValue = targetCircleSize)

  val painter = when (deviceUi.deviceType) {
    DeviceType.MOBILE -> painterResource(resource = Res.drawable.mobile)
    DeviceType.DESKTOP -> painterResource(Res.drawable.laptop)
    else -> null
  }

  Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
    if (isSending) {
      val progress = deviceUi.activityState.progressPercentage / 100.0f
      CircularProgressIndicator(
        progress = { progress },
        modifier = Modifier.size(56.dp),
        color = KdTheme.colors.accent,
        strokeWidth = 2.dp
      )
    }

    Box(
      modifier = Modifier
        .size(circleSize)
        .background(
          color = KdTheme.colors.bg2,
          shape = CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      painter?.let {
        Icon(
          painter = it,
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = KdTheme.colors.text2
        )
      }
    }
  }
}

@Composable
internal expect fun Modifier.deviceAdditions(
  deviceUi: DeviceUi,
  onDeviceActionListener: OnDeviceActionListener
): Modifier


interface OnDeviceActionListener {
  fun onDeviceClick(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  fun onSendData(deviceUi: DeviceUi, onDataToSend: OnDataToSend) {
    throw IllegalStateException("Not implemented")
  }

  fun onAddToTrusted(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

  fun onRemoveTrust(deviceUi: DeviceUi) {
    throw IllegalStateException("Not implemented")
  }

}
