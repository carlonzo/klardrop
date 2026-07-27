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
import com.carlom.klardrop.common.communication.OutgoingTransferAnchor
import com.carlom.klardrop.common.communication.Server
import com.carlom.klardrop.common.communication.message.AckMessageHandler
import com.carlom.klardrop.common.communication.message.ConnectionInfoMessageHandler
import com.carlom.klardrop.common.communication.message.FileMessageHandler
import com.carlom.klardrop.common.communication.message.MessageHandlersImpl
import com.carlom.klardrop.common.communication.message.MessageType
import com.carlom.klardrop.common.communication.message.TextMessageHandler
import com.carlom.klardrop.common.communication.router.IncomingAuthorizer
import com.carlom.klardrop.common.communication.router.MessagesRouterImpl
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.mdns.NearbyClient
import com.carlom.klardrop.common.mdns.NearbyReceiverConnectionHandlerFactory
import com.carlom.klardrop.common.network.NetworkLifecycleMonitor
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.MessageReceiverImpl
import com.carlom.klardrop.common.trust.ClipboardSyncManager
import com.carlom.klardrop.common.trust.ClipboardSyncMessageHandler
import com.carlom.klardrop.common.trust.PairingProtocolCoordinator
import com.carlom.klardrop.common.trust.TrustChecker
import com.carlom.klardrop.common.trust.TrustCrypto
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.TrustMessageWrapper
import com.carlom.klardrop.common.trust.TrustPairingRequestHandler
import com.carlom.klardrop.common.trust.TrustPairingResponseHandler
import com.carlom.klardrop.common.trust.TrustRevocationMessageHandler
import com.carlom.klardrop.common.trust.TrustStorage
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
   * Platform hook that keeps the host process alive for the length of an outbound file transfer.
   * Only Android supplies a real one (a foreground service); everything else stays
   * [OutgoingTransferAnchor.None].
   */
  private val outgoingTransferAnchor: OutgoingTransferAnchor = OutgoingTransferAnchor.None,
) {

  private val serializer by lazy { MessageSerializer(protoBuf, coroutines) }

  // Trust system components - now injected via constructor
  // (trustStorage is passed via constructor parameter)

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
      MessageType.FILE to fileMessageHandler,
      MessageType.ACK_READY to AckMessageHandler(),
      MessageType.ACK_RECEIVED to AckMessageHandler(),
      MessageType.TRUST_PAIRING_REQUEST to TrustPairingRequestHandler(serializer, trustManager),
      MessageType.TRUST_PAIRING_RESPONSE to TrustPairingResponseHandler(serializer, trustManager),
      MessageType.TRUST_REVOCATION to TrustRevocationMessageHandler(serializer, trustManager),
      MessageType.CLIPBOARD_SYNC to ClipboardSyncMessageHandler(serializer, clipboardSyncManager),
      MessageType.CONNECTION_INFO to ConnectionInfoMessageHandler(serializer),
    )

    MessageHandlersImpl(handlers)
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
      NearbyReceiverConnectionHandlerFactory(fileManager, coroutines, incomingAuthorizer, messageRepository, clock),
      visibleDevices,
      messageReceiver,
      protoBuf,
      trustManager,
      ackTimeoutConfig,
      heartbeatConfig,
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
      trustManager,
      serializer,
      messageRepository,
      ackTimeoutConfig,
      outgoingTransferAnchor,
    )
  }

  fun client() = client
  fun server() = server
  fun bleServerListener() = bleServerListener
  fun bleEagerConnector() = bleEagerConnector
  fun eagerReachabilityConnector() = eagerReachabilityConnector
  fun messenger() = messenger
  fun messageReceiver() = messageReceiver
  fun trustManager() = trustManager
  fun pairingProtocolCoordinator() = pairingProtocolCoordinator ?: initializePairingProtocolCoordinator()
  fun trustStorage() = trustStorage
  fun clipboardSyncManager() = clipboardSyncManager

  private fun initializePairingProtocolCoordinator(): PairingProtocolCoordinator {
    if (pairingProtocolCoordinator == null) {
      pairingProtocolCoordinator = PairingProtocolCoordinator(trustManager, messenger)
    }
    return pairingProtocolCoordinator!!
  }


  @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
  internal fun connectionsPool() = connectionsPool

  fun reachability() = connectionsPool.reachability
}
