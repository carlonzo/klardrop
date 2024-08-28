package com.carlom.klardrop.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.history.DevicesDbDataSource
import com.carlom.klardrop.common.history.HistoryDbDataSource
import com.carlom.klardrop.common.history.HistoryMessage
import com.carlom.klardrop.common.history.HistoryMessagePayload
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveTransferUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile

class MessageScreenPresenter(
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messenger: Messenger,
  private val historyDbDataSource: HistoryDbDataSource,
  private val deviceDbDataSource: DevicesDbDataSource
) {

  constructor(commonComponent: CommonComponent) : this(
    currentDeviceProvider = commonComponent.currentDeviceProvider(),
    messenger = commonComponent.messenger(),
    historyDbDataSource = commonComponent.historyDbDataSource(),
    deviceDbDataSource = commonComponent.devicesDbDataSource()
  )

  val pagerNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


  /*
   * 1. listen for old messages from pager from DB
   * 3. listen for new messages from Messenger
   */
  fun getMessagesFlow(deviceId: String): Flow<List<HistoryMessage>> {

    val historicalMessagesFlow = flow<DeviceInfo> {
      emit(deviceDbDataSource.getDevice(deviceId)!!)
    }.flatMapConcat { deviceInfo ->
      // historical messages
      combine(
        historyDbDataSource.getMessagesFromDeviceAsFlow(deviceInfo),
        historyDbDataSource.getMessagesToDeviceAsFlow(deviceInfo)
      ) { fromMessages, toMessages ->
        (fromMessages + toMessages).sortedByDescending { it.timestamp }
      }
    }

    val newMessagesFlow = messenger.receive()
      .flatMapMerge { receiveFlow ->
        receiveFlow.transformWhile { receiveTransferUpdate ->
          emit(receiveTransferUpdate)
          !receiveTransferUpdate.status.isFinished()
        }
      }
      .map { receiveTransferUpdate ->
        // Convert ReceiveTransferUpdate to HistoryMessage if needed
        receiveTransferUpdate.toHistoryMessage()
      }

    return combine(
      historicalMessagesFlow,
      newMessagesFlow
    ) { historicalMessages, newMessage ->
      (historicalMessages + newMessage).sortedByDescending { it.timestamp }
    }
  }

  private fun ReceiveTransferUpdate.toHistoryMessage(): HistoryMessage {
    // Convert ReceiveTransferUpdate to HistoryMessage
    // Implement this method based on your actual data structure
    return HistoryMessage(
      id = 99, //TODO
      device = this.device,
      timestamp = 999, //TODO
      payload = HistoryMessagePayload.TextMessagePayload("Hello"), //TODO

    )
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

  }

  internal data class HeaderUIState(
    val deviceName: String
  )

  internal data class MessageUI(
    val message: Message,
    val timestamp: Long,
    val status: ReceiveMessageStatus,
    val device: DeviceInfo
  ) {
    companion object {
      fun fromHistoryMessage(historyMessage: HistoryMessage): MessageUI {


        return MessageUI(
          message = TextMessage(text = (historyMessage.payload as HistoryMessagePayload.TextMessagePayload).content), //TODO
          timestamp = historyMessage.timestamp,
          status = ReceiveMessageStatus.Completed,
          device = historyMessage.device
        )
      }
    }
  }

}