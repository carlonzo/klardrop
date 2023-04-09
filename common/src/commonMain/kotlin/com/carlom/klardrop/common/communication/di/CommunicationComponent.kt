package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.Client
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectionsPool
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.ReceivedMessagesBroadcast
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouter
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.KnownDevicesRepository
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.SingletonProvider
import kotlinx.serialization.protobuf.ProtoBuf

class CommunicationModule(
  private val coroutines: Coroutines,
  private val knownDevicesRepository: KnownDevicesRepository,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val platformDependencies: InternalPlatformDependencies
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  private val messageHandlers = SingletonProvider<MessageHandlers> {
    MessageHandlersImpl(
      mapOf(
        MessageType.FILE to FileMessageHandler({ platformDependencies.getStoragePath() }, serializer, platformDependencies.fileResolver()),

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
      knownDevicesRepository,
      incomingMessagesRouter(),
      localPropertiesRepository,
      serializer
    )
  }
  private val server = SingletonProvider {
    Server(
      localPropertiesRepository,
      connectionsPool(),
      coroutines,
      knownDevicesRepository,
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
