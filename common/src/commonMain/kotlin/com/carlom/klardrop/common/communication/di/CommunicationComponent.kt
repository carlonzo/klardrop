package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.*
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.serialization.protobuf.ProtoBuf

class CommunicationModule(
  private val coroutines: Coroutines,
  private val localPropertiesRepository: LocalPropertiesRepository,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val clock: Clock,
  private val fileManager: FileManager,
  private val nearbyClient: NearbyClient
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  private val messageHandlers by lazy {
    MessageHandlersImpl(
      mapOf(
        MessageType.FILE to FileMessageHandler(serializer, fileManager, clock, coroutines)
      )
    )
  }

  private val receivedMessagesBroadcast by lazy {
    ReceivedMessagesBroadcast(coroutines)
  }

  private val connectionsPool by lazy { ConnectionsPoolImpl() }
  private val messagesRouter by lazy {
    MessagesRouterImpl(
      messageHandlers,
      serializer,
      coroutines,
      receivedMessagesBroadcast
    )
  }

  private val client by lazy {
    ClientImpl(
      connectionsPool,
      coroutines,
      messagesRouter,
      localPropertiesRepository,
      serializer,
      visibleDevices
    )
  }

  private val server by lazy {
    Server(
      localPropertiesRepository,
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer
    )
  }
  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool,
      client(),
      coroutines,
      nearbyClient
    )
  }

  fun client() = client
  fun server() = server
  fun messenger() = messenger
}
