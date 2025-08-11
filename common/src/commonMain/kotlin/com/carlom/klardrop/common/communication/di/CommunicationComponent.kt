package com.carlom.klardrop.common.communication.di

import androidx.annotation.VisibleForTesting
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.AckMessageHandler
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.TextMessageHandler
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.trust.ClipboardSyncManager
import com.carlom.klardrop.common.trust.ClipboardSyncMessageHandler
import com.carlom.klardrop.common.trust.InMemoryTrustStorage
import com.carlom.klardrop.common.trust.PairingProtocolCoordinator
import com.carlom.klardrop.common.trust.TrustChecker
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.TrustMessageWrapper
import com.carlom.klardrop.common.trust.TrustPairingRequestHandler
import com.carlom.klardrop.common.trust.TrustPairingResponseHandler
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.trust.TrustedMessageHandler
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.serialization.protobuf.ProtoBuf

class CommunicationModule(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val clock: Clock,
  private val fileManager: FileManager,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messageRepository: MessageRepository,
  private val clipboardManager: ClipboardManager
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  // Trust system components
  private val trustStorage: TrustStorage by lazy {
    // TODO to change to already implemented persisted trust storage
    InMemoryTrustStorage()
  }

  private val trustCrypto = TrustCrypto()

  private val trustChecker = object : TrustChecker {
    override suspend fun isTrusted(deviceId: String): Boolean = trustStorage.isTrusted(deviceId)
  }

  // TrustManager is now a pure domain component without messenger dependency
  private val trustManager by lazy {
    TrustManager(trustCrypto, trustStorage, clock, currentDeviceProvider)
  }

  // PairingProtocolCoordinator will be initialized manually after DI cycle is complete
  private var pairingProtocolCoordinator: PairingProtocolCoordinator? = null

  private val trustMessageWrapper by lazy {
    TrustMessageWrapper(trustManager, serializer)
  }

  // Clipboard sync components
  private val clipboardSyncManager by lazy {
    ClipboardSyncManager(clipboardManager, visibleDevices, trustManager, clock, coroutines, lazy { messenger })
  }

  private val messageHandlers by lazy {
    MessageHandlersImpl(
      mapOf(
        MessageType.TEXT to TextMessageHandler(serializer, messageRepository),
        MessageType.FILE to FileMessageHandler(serializer, fileManager, clock, coroutines, messageRepository),

        MessageType.ACK_READY to AckMessageHandler(),
        MessageType.ACK_RECEIVED to AckMessageHandler(),
        MessageType.TRUST_PAIRING_REQUEST to TrustPairingRequestHandler(serializer, trustManager),
        MessageType.TRUST_PAIRING_RESPONSE to TrustPairingResponseHandler(serializer, trustManager),
        MessageType.TRUSTED_MESSAGE to TrustedMessageHandler(serializer, trustManager),
        MessageType.CLIPBOARD_SYNC to ClipboardSyncMessageHandler(serializer, clipboardSyncManager)
      )
    )
  }

  private val connectionsPool by lazy { ConnectionsPoolImpl() }
  private val messagesRouter by lazy {
    MessagesRouterImpl(
      messageHandlers,
      serializer,
      coroutines,
      messageReceiver,
      messageRepository
    )
  }

  private val client by lazy {
    ClientImpl(
      connectionsPool,
      coroutines,
      messagesRouter,
      serializer,
      visibleDevices,
      currentDeviceProvider
    )
  }

  private val messageReceiver: MessageReceiver by lazy {
    MessageReceiverImpl(coroutines, visibleDevices)
  }

  private val server by lazy {
    Server(
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

  private val nearbyClient = lazy {
    NearbyClient(
      coroutines,
      currentDeviceProvider,
      fileManager,
    )
  }

  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool,
      client(),
      coroutines,
      nearbyClient,
      messageReceiver,
      lazy { trustChecker },
      trustMessageWrapper
    )
  }

  fun client() = client
  fun server() = server
  fun messenger() = messenger
  fun messageReceiver() = messageReceiver
  fun trustManager() = trustManager
  fun pairingProtocolCoordinator() = pairingProtocolCoordinator ?: initializePairingProtocolCoordinator()
  fun trustStorage() = trustStorage
  fun trustMessageWrapper() = trustMessageWrapper
  fun clipboardSyncManager() = clipboardSyncManager

  private fun initializePairingProtocolCoordinator(): PairingProtocolCoordinator {
    if (pairingProtocolCoordinator == null) {
      pairingProtocolCoordinator = PairingProtocolCoordinator(trustManager, messenger)
    }
    return pairingProtocolCoordinator!!
  }


  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun connectionsPool() = connectionsPool
}
