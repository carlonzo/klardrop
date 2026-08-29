package com.carlom.klardrop.common.communication.di

import androidx.annotation.VisibleForTesting
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.ble.BleTransport
import com.carlom.klardrop.common.communication.AckTimeoutConfig
import com.carlom.klardrop.common.communication.BleEagerConnector
import com.carlom.klardrop.common.communication.BleServerListener
import com.carlom.klardrop.common.communication.ClientImpl
import com.carlom.klardrop.common.communication.EagerReachabilityConnector
import com.carlom.klardrop.common.communication.HeartbeatConfig
import com.carlom.klardrop.common.communication.ConnectionsPoolImpl
import com.carlom.klardrop.common.communication.MessageSerializer
import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerImpl
import com.carlom.klardrop.common.communication.TransferAnchor
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessageHandler
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.Message
import com.carlom.klardrop.common.communication.message.MessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlers
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessageHandler
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandler
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.trust.ClipboardSyncManager
import com.carlom.klardrop.common.trust.ClipboardSyncMessageHandler
import com.carlom.klardrop.common.trust.PairingProtocolCoordinator
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.TrustPairingRequestHandler
import com.carlom.klardrop.common.trust.TrustPairingResponseHandler
import com.carlom.klardrop.common.trust.TrustRevocationMessageHandler
import com.carlom.klardrop.common.trust.TrustStorage
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.Coroutines
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.coroutines.flow.MutableStateFlow

class CommunicationModule(
  private val coroutines: Coroutines,
  private val visibleDevices: VisibleDevices,
  private val protoBuf: ProtoBuf,
  private val clock: Clock,
  private val fileManager: FileManager,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messageRepository: MessageRepository,
  private val clipboardManager: ClipboardManager,
  private val trustStorage: TrustStorage,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val heartbeatConfig: HeartbeatConfig = HeartbeatConfig.DEFAULT,
  private val bleTransport: BleTransport? = null,
  /**
   * Optional override for the per-message authorization gate. Production wires the
   * default trust-aware authorizer; integration tests can pass an auto-accept stub
   * to keep their assertions focused on transport behavior rather than the prompt UX.
   */
  private val incomingAuthorizerOverride: IncomingAuthorizer? = null,
  private val networkLifecycleMonitor: NetworkLifecycleMonitor? = null,
  /**
   * Platform hook that keeps the host process alive and awake for the length of a file transfer,
   * outbound (via [MessengerImpl]) and inbound (via [MessagesRouterImpl] for Klardrop transfers
   * and Nearby inbound for Nearby ones) alike.
   */
  private val transferAnchor: TransferAnchor = TransferAnchor.None,
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  // T10: the server's bound port, published by Server on bind and read by the client's
  // punch-through dial so it can bind its sockets to our own listening port.
  private val serverPort = MutableStateFlow(0)

  // Trust system components - now injected via constructor
  // (trustStorage is passed via constructor parameter)

  private val trustCrypto = TrustCrypto()

  private val trustManager by lazy {
    TrustManager(trustCrypto, trustStorage, clock, currentDeviceProvider)
  }


  // Clipboard sync components
  private val clipboardSyncManager by lazy {
    ClipboardSyncManager(clipboardManager, visibleDevices, trustManager, clock, coroutines, lazy { messenger })
  }

  private val fileMessageHandler by lazy {
    FileMessageHandler(fileManager, clock, coroutines, messageRepository)
  }

  private val messageHandlers by lazy {
    val handlers = mapOf(
      MessageType.TEXT to TextMessageHandler(serializer, messageRepository),
      MessageType.TRUST_PAIRING_REQUEST to TrustPairingRequestHandler(serializer, trustManager),
      MessageType.TRUST_PAIRING_RESPONSE to TrustPairingResponseHandler(serializer, trustManager),
      MessageType.TRUST_REVOCATION to TrustRevocationMessageHandler(serializer, trustManager),
      MessageType.CLIPBOARD_SYNC to ClipboardSyncMessageHandler(serializer, clipboardSyncManager),
      MessageType.CONNECTION_INFO to ConnectionInfoMessageHandler(serializer),
    )
    MessageHandlers { type ->
      @Suppress("UNCHECKED_CAST")
      handlers[type] as MessageHandler<Message, SendMessageRequest>?
    }
  }

  private val connectionsPool by lazy { ConnectionsPoolImpl(coroutines, networkLifecycleMonitor, currentDeviceProvider) }

  /**
   * Single shared authorizer instance — process-scoped first-contact set must be the same
   * one consulted by both the Klardrop router and the Nearby receiver, otherwise a user
   * who accepts a text on Klardrop would still be re-prompted for the same device's text
   * over Nearby (and vice versa).
   */
  private val incomingAuthorizer by lazy {
    incomingAuthorizerOverride ?: IncomingAuthorizer(trustManager)
  }

  private val messagesRouter by lazy {
    MessagesRouterImpl(
      messageHandlers,
      fileMessageHandler,
      serializer,
      coroutines,
      messageReceiver,
      trustManager,
      incomingAuthorizer,
      onPeerLiveness = visibleDevices::touchLastSeen,
      transferAnchor = transferAnchor,
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
      trustManager,
      ackTimeoutConfig,
      heartbeatConfig,
      bleTransport,
      serverPort,
    )
  }

  private val bleServerListener by lazy {
    bleTransport?.let {
      BleServerListener(
        coroutines = coroutines,
        bleTransport = it,
        serializer = serializer,
        currentDeviceProvider = currentDeviceProvider,
        messagesRouter = messagesRouter,
        connectionsPool = connectionsPool,
        visibleDevices = visibleDevices,
        trustManager = trustManager,
        ackTimeoutConfig = ackTimeoutConfig,
        heartbeatConfig = heartbeatConfig,
      )
    }
  }

  private val bleEagerConnector by lazy {
    bleTransport?.let {
      BleEagerConnector(
        coroutines = coroutines,
        visibleDevices = visibleDevices,
        currentDeviceProvider = currentDeviceProvider,
        client = client,
        connectionsPool = connectionsPool,
      )
    }
  }

  private val eagerReachabilityConnector by lazy {
    networkLifecycleMonitor?.let {
      EagerReachabilityConnector(
        coroutines = coroutines,
        visibleDevices = visibleDevices,
        currentDeviceProvider = currentDeviceProvider,
        client = client,
        connectionsPool = connectionsPool,
        networkLifecycleMonitor = it,
      )
    }
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
      createNearbyReceiver = {
        NearbyReceiverConnectionHandler(fileManager, coroutines, incomingAuthorizer, messageRepository, transferAnchor)
      },
      visibleDevices,
      messageReceiver,
      protoBuf,
      trustManager,
      ackTimeoutConfig,
      heartbeatConfig,
      serverPort,
    )
  }

  private val messenger: Messenger by lazy {
    MessengerImpl(
      visibleDevices,
      connectionsPool,
      client(),
      coroutines,
      currentDeviceProvider,
      fileManager,
      messageReceiver,
      trustManager,
      serializer,
      messageRepository,
      ackTimeoutConfig,
      transferAnchor,
    )
  }

  private val pairingProtocolCoordinator by lazy {
    PairingProtocolCoordinator(trustManager, messenger)
  }

  fun client() = client
  fun server() = server
  fun bleServerListener() = bleServerListener
  fun bleEagerConnector() = bleEagerConnector
  fun eagerReachabilityConnector() = eagerReachabilityConnector
  fun messenger() = messenger
  fun messageReceiver() = messageReceiver
  fun trustManager() = trustManager
  fun pairingProtocolCoordinator() = pairingProtocolCoordinator
  fun trustStorage() = trustStorage
  fun clipboardSyncManager() = clipboardSyncManager


  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun connectionsPool() = connectionsPool

  fun reachability() = connectionsPool.reachability
}
