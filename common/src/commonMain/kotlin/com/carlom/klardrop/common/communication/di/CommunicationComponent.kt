package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.mdns.NearbyShareServer
import com.carlom.klardrop.common.persistence.LocalPropertiesRepository
import com.carlom.klardrop.common.receiver.TransferReceiver
import com.carlom.klardrop.common.receiver.TransferReceiverImpl
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
  private val internalPlatformDependencies: InternalPlatformDependencies,
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
      transferReceiver
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

  private val server by lazy {
    Server(
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer,
      currentDeviceProvider
    )
  }

  private val transferReceiver: TransferReceiver by lazy {
    TransferReceiverImpl(coroutines, visibleDevices)
  }

  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool,
      client(),
      coroutines,
      nearbyClient,
      transferReceiver
    )
  }

  private val nearbyServer by lazy {
    NearbyShareServer(
      coroutines,
      NearbyReceiverConnectionHandlerFactory(internalPlatformDependencies, fileManager, coroutines),
      visibleDevices,
      transferReceiver,
    )
  }

  private val nearbyClient by lazy {
    NearbyClient(
      coroutines,
      currentDeviceProvider,
      internalPlatformDependencies,
      fileManager,
    )
  }


  fun nearbyServer(): NearbyShareServer {
    return nearbyServer
  }

  fun client() = client
  fun server() = server
  fun messenger() = messenger

  fun messageReceiver() = transferReceiver
}
