package com.carlom.klardrop.common.communication.di

import com.carlom.klardrop.common.FileManager
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
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
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
  private val currentDeviceProvider: CurrentDeviceProvider
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  // messageReceiver needs to be defined before messenger and messagesRouter
  private val messageReceiver: MessageReceiver by lazy {
    MessageReceiverImpl(coroutines, visibleDevices)
  }

  // nearbyClient needs to be defined before messenger
  private val nearbyClient by lazy {
    NearbyClient(
      coroutines,
      currentDeviceProvider,
      fileManager,
    )
  }

  private val messageHandlers by lazy {
    MessageHandlersImpl(
      mapOf(
        MessageType.FILE to FileMessageHandler(serializer, fileManager, clock, coroutines)
      )
    )
  }

  private val connectionsPool by lazy { ConnectionsPoolImpl() }

  // Order to break cycle with property injection:
  // 1. messengerInstance (depends on clientInstance in constructor)
  // 2. messagesRouterInstance (depends on messengerInstance in constructor)
  // 3. clientInstance (constructor is simple, property injection of messagesRouterInstance)

  // Forward declaration for types if needed by IDE/compiler, actual init order is by lazy
  private val clientInstance: ClientImpl by lazy {
    ClientImpl(
      connectionsPool = connectionsPool,
      coroutines = coroutines,
      // messagesRouter is removed from constructor
      serializer = serializer,
      visibleDevices = visibleDevices,
      currentDeviceProvider = currentDeviceProvider
    ).also {
      // Property injection after construction
      it.messagesRouter = messagesRouterInstance
    }
  }

  private val messengerInstance: MessengerImpl by lazy {
    MessengerImpl(
      visibleDevices = visibleDevices,
      connectionsPool = connectionsPool,
      client = clientInstance, // ClientImpl instance
      coroutines = coroutines,
      nearbyClient = nearbyClient,
      messageReceiver = messageReceiver
    )
  }

  private val messagesRouterInstance: MessagesRouter by lazy { // Public type is MessagesRouter
    MessagesRouterImpl(
      handlers = messageHandlers,
      messageSerializer = serializer,
      coroutines = coroutines,
      messengeReceiver = messageReceiver,
      ackDelegate = messengerInstance // MessengerImpl instance
    )
  }

  // Ensure public accessors use the correctly named instances
  // Client is now clientInstance
  // MessagesRouter is now messagesRouterInstance

  private val server by lazy {
    Server(
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer,
      currentDeviceProvider
    )
  }

  private val nearbyServer by lazy {
    NearbyShareServer(
      coroutines,
      NearbyReceiverConnectionHandlerFactory(fileManager, coroutines),
      visibleDevices,
      messageReceiver,
    )
  }

  // Public accessors
  fun nearbyServer(): NearbyShareServer = nearbyServer
  fun client(): Client = clientInstance // Updated to clientInstance
  fun server() = server
  fun messenger(): Messenger = messengerInstance
  fun messageReceiver() = messageReceiver
  // It might be useful to provide MessagesRouter and AckDelegate publicly if other modules need them
  fun messagesRouter(): MessagesRouter = messagesRouterInstance
  fun ackDelegate(): AckDelegate = messengerInstance
}
