package com.carlom.klardrop.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.history.HistoryDbDataSource
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map

class MessageScreenPresenter(
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messenger: Messenger,
  private val historyDbDataSource: HistoryDbDataSource
) {

  constructor(commonComponent: CommonComponent) : this(
    currentDeviceProvider = commonComponent.currentDeviceProvider(),
    messenger = commonComponent.messenger()
  )

  private fun getCombinedMessages(otherDeviceId: String) : Flow<List<ReceiveMessageUpdate>> {

   val currentDeviceId = "abc1234" // TODO

    // TODO paging?
    val otherDeviceMessages = historyDbDataSource.getMessagesForDeviceAsFlow(otherDeviceId, limit = 10, offset = 0)
    val currentDeviceMessages = historyDbDataSource.getMessagesForDeviceAsFlow(currentDeviceId, limit = 10, offset = 0)

    otherDeviceMessages.combine(currentDeviceMessages) { otherDeviceMessages, currentDeviceMessages ->
      (otherDeviceMessages + currentDeviceMessages).sortedBy { it.timestamp }
    }.map {
      it.map { message -> ReceiveMessageUpdate(
      device = message.device,
        status = ReceiveMessageStatus.Completed,
        messages =
      ) }
    }

  }

  @Composable
  internal fun header(): HeaderUIState {
    val currentDevice by currentDeviceProvider.flow.collectAsState()

    return HeaderUIState(
      deviceName = currentDevice.deviceName
    )
  }


  @Composable
  fun messages() {
    val receivedMessages by messenger.receive().collectAsState(emptyFlow())

    receivedMessages.co
  }

  internal data class HeaderUIState(
    val deviceName: String
  )
}