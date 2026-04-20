package com.carlom.klardrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun IncomingBannerStack(
  state: DiscoveryScreenState,
  callbacks: ReceiveNotificationsCallbacks
) {
  val updates = state.receivingMessages.values.toList()
  if (updates.isEmpty()) return

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    updates.forEach { update ->
      ReceiveNotification(
        receiveUpdate = update,
        callbacks = callbacks
      )
    }
  }
}
