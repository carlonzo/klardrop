package com.carlom.klardrop.common.communication.di

import androidx.annotation.VisibleForTesting
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.UnifiedServer
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.serialization.protobuf.ProtoBuf

class CommunicationModule(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val clock: Clock,
  private val fileManager: FileManager,
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  private val messageHandlers by lazy {
    MessageHandlersImpl(
      mapOf(
        MessageType.FILE to FileMessageHandler(serializer, fileManager, clock, coroutines)
      )
    )
  }

  private val connectionsPool by lazy { ConnectionsPoolImpl() }
  private val messagesRouter by lazy {
    MessagesRouterImpl(
      messageHandlers,
      serializer,
      coroutines,
      messageReceiver
    )
  }

  private val client by lazy {
    ClientImpl(
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer,
      visibleDevices,
      currentDeviceProvider,
    )
  }

  private val messageReceiver: MessageReceiver by lazy {
    MessageReceiverImpl(coroutines, visibleDevices)
  }

  private val unifiedServer by lazy {
    UnifiedServer(
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer,
      currentDeviceProvider,
      NearbyReceiverConnectionHandlerFactory(fileManager, coroutines),
      visibleDevices,
      messageReceiver,
      protoBuf
    )
  }

  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool,
      client(),
      coroutines,
      nearbyClient,
      messageReceiver
    )
  }

  private val nearbyClient by lazy {
    NearbyClient(
      coroutines,
      currentDeviceProvider,
      fileManager,
    )
  }


  fun client() = client
  fun unifiedServer() = unifiedServer
  fun messenger() = messenger
  fun messageReceiver() = messageReceiver

  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun connectionsPool() = connectionsPool
}
