package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class ReceivedMessagesBroadcast(
  private val coroutines: Coroutines
) {

  private val broadcastScope = CoroutineScope(coroutines.ioDispatcher)

  private val sharedFlow = MutableSharedFlow<Message>()
  val flow: Flow<Message> = sharedFlow.asSharedFlow()

  internal fun onNewMessage(message: Message) {
    broadcastScope.launch { sharedFlow.emit(message) }
  }

}