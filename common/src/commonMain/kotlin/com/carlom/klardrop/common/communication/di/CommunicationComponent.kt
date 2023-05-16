package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.*
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider
import kotlinx.serialization.protobuf.ProtoBuf

class CommunicationModule(
  private val coroutines: Coroutines,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val clock: Clock,
  private val fileManager: FileManager,
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  private val messageHandlers = SingletonProvider<MessageHandlers> {
    MessageHandlersImpl(
      mapOf(
        MessageType.FILE to FileMessageHandler(serializer, fileManager, clock, coroutines)
      )
    )
  }

  private val receivedMessagesBroadcast = SingletonProvider {
    ReceivedMessagesBroadcast(coroutines)
  }

  private val connectionsPool = SingletonProvider<ConnectionsPool> { ConnectionsPoolImpl() }
  private val messagesRouter = SingletonProvider<MessagesRouter> {
    MessagesRouterImpl(
      messageHandlers.get(),
      serializer,
      coroutines,
      receivedMessagesBroadcast.get()
    )
  }

  private val client = SingletonProvider<Client> {
    ClientImpl(
      connectionsPool(),
      coroutines,
      incomingMessagesRouter(),
      localPropertiesRepository,
      serializer,
      visibleDevices
    )
  }
  private val server = SingletonProvider {
    Server(
      localPropertiesRepository,
      connectionsPool(),
      coroutines,
      incomingMessagesRouter(),
      serializer
    )
  }
  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool(),
      client(),
      coroutines,
    )
  }

  fun connectionsPool() = connectionsPool.get()
  fun incomingMessagesRouter() = messagesRouter.get()
  fun client() = client.get()
  fun server() = server.get()
  fun messenger() = messenger
}
