package com.carlom.klardrop.common

import com.carlom.klardrop.common.communication.FrameCipher
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.MessageAcknowledgment
import com.carlom.klardrop.common.communication.message.PongMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.router.MessagesRouter
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex

open class FakeMessagesRouter: MessagesRouter {
  override suspend fun onMessageIncoming(
    fromDeviceId: String,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    ackCallback: (suspend (MessageAcknowledgment) -> Unit),
    pongCallback: (suspend (PongMessage) -> Unit),
    writeLock: Mutex,
    cipher: FrameCipher,
  ) {

  }

  override suspend fun <S : SendMessageRequest> onSendingMessage(
    toDeviceId: String,
    sendMessageRequest: S,
    writeChannel: ByteWriteChannel,
    readChannel: ByteReadChannel,
    progress: MutableSharedFlow<MessengerSendProgress>,
    awaitReadyAck: suspend () -> Unit,
    writeLock: Mutex,
    cipher: FrameCipher,
  ) {
  }

}