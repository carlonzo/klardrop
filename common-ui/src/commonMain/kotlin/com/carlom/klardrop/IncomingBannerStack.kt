package com.carlom.klardrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.carlom.klardrop.theme.KdTheme

@Composable
internal fun IncomingBannerStack(
    state: DiscoveryScreenState,
    callbacks: ReceiveNotificationsCallbacks,
) {
    val updates = state.receivingMessages
    if (updates.isEmpty()) return

    val spacing = KdTheme.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.s3, vertical = spacing.s2),
        verticalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
        updates.forEach { (id, update) ->
            ReceiveNotification(
                receiveUpdate = update,
                onClicked = { callbacks.onReceivedCardClicked(update) },
                onDismissed = { callbacks.onCardDismissed(id) },
                onConnectionInfoAccepted = { callbacks.onConnectionInfoAccepted(it) },
            )
        }
    }
}
