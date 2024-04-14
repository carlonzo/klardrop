package com.carlom.klardrop.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.di.CommonComponent
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.history.DevicesDbDataSource
import com.carlom.klardrop.common.history.HistoryDbDataSource
import com.carlom.klardrop.common.history.HistoryMessage
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.receiver.ReceiveTransferUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlin.random.Random

class MessageScreenPresenter(
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messenger: Messenger,
  private val historyDbDataSource: HistoryDbDataSource,
  private val deviceDbDataSource: DevicesDbDataSource
) {

  constructor(commonComponent: CommonComponent) : this(
    currentDeviceProvider = commonComponent.currentDeviceProvider(),
    messenger = commonComponent.messenger()
  )

  val pagerNotifier = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


  /*
   * 1. listen for old messages from pager from DB
   * 3. listen for new messages from Messenger
   */


  private fun flatMessengerReceiver() = flow<> {


    fun CoroutineScope.listenNewMessagesReceived(flow: Flow<ReceiveTransferUpdate>) {

      val receiveId = Random.nextInt()



        flow.transformWhile {
          emit(it)

          !it.status.isFinished()
        }
          .flowOn(this)
          . {  }
          .collect { receiveMessageUpdate ->
          screenStateFlow.update {
            val messages = it.receivingMessages.toMutableMap()
            messages[receiveId] = receiveMessageUpdate

            it.copy(
              receivingMessages = messages
            )
          }
        }



    }


    messenger.receive().collect {

    }

  }

  private fun messagesFromOtherDevice(otherDeviceId: String): Flow<List<ReceiveTransferUpdate>> = flow {
    val otherDevice = deviceDbDataSource.getDevice(otherDeviceId) ?: throw IllegalStateException("Other device not found in DB")

    // live transfers
    messenger.receive().

    var receivedMessages: MutableList<HistoryMessage> = mutableListOf()

    (pagerNotifier.onStart { emit(Unit) }
      .flatMapLatest { historyDbDataSource.getMessagesFromDeviceAsFlow(otherDevice, limit = 20, offset = receivedMessages.size.toLong()) })
      .combine()

      .collect {

        receivedMessages = receivedMessages.toMutableList()
        receivedMessages.addAll(it)

        emit(receivedMessages)
      }
  }


  private fun HistoryMessage.toReceiveTransferUpdate(): ReceiveTransferUpdate {



    return ReceiveTransferUpdate(
      device = device,
      status = ReceiveMessageStatus.Completed,
      messages = listOf(
        Message()
      )
    )
  }




  private fun getCombinedMessages(otherDeviceId: String): Flow<List<ReceiveTransferUpdate>> = flow {

    val otherDevice = deviceDbDataSource.getDevice(otherDeviceId) ?: throw IllegalStateException("Other device not found in DB")
    val thisDevice = currentDeviceProvider.get().asDeviceInfo()

    // TODO paging?
    val otherDeviceMessagesFlow = historyDbDataSource.getMessagesFromDeviceAsFlow(otherDevice, limit = 10, offset = 0)
    val thisDeviceMessagesFlow = historyDbDataSource.getMessagesFromDeviceAsFlow(thisDevice, limit = 10, offset = 0)

    combine(otherDeviceMessagesFlow, thisDeviceMessagesFlow)
    otherDeviceMessagesFlow.combine(thisDeviceMessagesFlow) { thisDeviceMessages, currentDeviceMessages ->
      (thisDeviceMessages + currentDeviceMessages).sortedBy { it.timestamp }
    }.map {
      it.map { message ->


        ReceiveTransferUpdate(
          device = message.device,
          status = ReceiveMessageStatus.Completed,
          messages = listOf(mess)
        )
      }
    }.collect {
      emit(it)
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

  }

  internal data class HeaderUIState(
    val deviceName: String
  )

  internal data class MessageUI (
    val message: Message,
    val timestamp: Long,
    val status: ReceiveMessageStatus,
    val device: DeviceInfo
  ){
    companion object {
      fun fromHistoryMessage(historyMessage: HistoryMessage): MessageUI {



        return MessageUI(
          message = historyMessage.payload.message,
          timestamp = historyMessage.timestamp,
          status = ReceiveMessageStatus.Completed,
          device = historyMessage.device
        )
      }
    }
  }

}