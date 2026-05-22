package com.carlom.klardrop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.theme.KdTheme

@Composable
internal fun IncomingBannerStack(
    state: DiscoveryScreenState,
    callbacks: ReceiveNotificationsCallbacks,
    modifier: Modifier = Modifier,
) {
    // Only surface receive updates that genuinely need user attention:
    //   - PendingAuthorization carrying real Text/File headers — these are
    //     untrusted-sender transfers that need an explicit accept/reject.
    //   - ConnectionInfoMessage payloads (Wi-Fi handoff) — user opts in to join.
    //
    // We explicitly require a Text/File header alongside PendingAuthorization
    // because control-plane traffic (PING/PONG, ACKs, pairing handshakes)
    // can churn the receive flow and we never want those to surface as a
    // "wants to send you a file" prompt — there's no file involved.
    // Trusted-device transfers transition Started → Completed silently.
    val updates = state.receivingMessages.filter { (_, update) ->
        val hasRealHeader = update.messages.any {
            it is TextMessage || it is FileMessage
        }
        val hasConnectionInfo = update.messages.any { it is ConnectionInfoMessage }
        (update.status is ReceiveMessageStatus.PendingAuthorization && hasRealHeader) ||
            hasConnectionInfo
    }
    if (updates.isEmpty()) return

    val spacing = KdTheme.spacing

    Column(
        modifier = modifier
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
