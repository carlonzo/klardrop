package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.MessengerSendProgress.Completed
import com.carlom.klardrop.common.communication.MessengerSendProgress.Error
import com.carlom.klardrop.common.communication.MessengerSendProgress.Pending
import com.carlom.klardrop.common.communication.message.FileMessage
import com.carlom.klardrop.common.communication.message.SendMessageRequest
import com.carlom.klardrop.common.communication.message.TextMessage
import com.carlom.klardrop.common.communication.message.TransferRejectedException
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.mdns.NearbyClientConnectionHandler
import com.carlom.klardrop.common.FileManager
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.persistence.MessageRepository
import com.carlom.klardrop.common.persistence.MessageType as PersistenceMessageType
import com.carlom.klardrop.common.persistence.SendStatus
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
import com.carlom.klardrop.common.utils.Coroutines
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.logLocal
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Messenger used to send messages
 */
interface Messenger {
  fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress>

  fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>>
}

class MessengerImpl(
  private val visibleDevices: VisibleDevices,
  private val connectionsPool: ConnectionsPool,
  private val client: Client,
  private val coroutines: Coroutines,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val fileManager: FileManager,
  private val messageReceiver: MessageReceiver,
  private val trustManager: com.carlom.klardrop.common.trust.TrustManager,
  private val messageSerializer: MessageSerializer,
  private val messageRepository: MessageRepository,
  private val ackTimeoutConfig: AckTimeoutConfig = AckTimeoutConfig.DEFAULT,
  private val transferAnchor: TransferAnchor = TransferAnchor.None,
) : Messenger {

  private val messengerScope = coroutines.newScope(SupervisorJob() + coroutines.ioDispatcher)

  /** Devices we have already re-dialled once to pick up a post-pairing identity binding. */
  private val authUpgradeAttempted = mutableSetOf<String>()

  override fun send(deviceId: String, messageRequest: SendMessageRequest): Flow<MessengerSendProgress> {
    log(
      "Messenger",
      "send() called: deviceId=$deviceId, messageType=${messageRequest.message.type}, messageId=${messageRequest.message.id}"
    )

    // replay = 1 so the caller can't lose the early emissions to a subscription race: the
    // producer below is launched immediately (on [messengerScope]/ioDispatcher) while the
    // caller only subscribes once `send()` has returned and `.collect` runs. A replay-less
    // SharedFlow drops anything emitted in that window — which is exactly Pending and, on a
    // fast local send, InProgress(0) too, so the UI never learned a transfer had begun.
    val flow = MutableSharedFlow<MessengerSendProgress>(replay = 1, extraBufferCapacity = 1)

    messengerScope.launch {
      // Anchor payload-bearing sends only. A file send waits on the receiver accepting and then
      // streams — minutes, all of it a window in which the platform may freeze or kill us out from
      // under the socket (see TransferAnchor). Text/control messages are a single frame
      // plus an ack; anchoring one would spin up a foreground service and flash a progress
      // notification for something already over by the time the user could read it.
      val anchored = messageRequest.message.hasPayload
      // Direction is part of the id: a device can be sending us one file while we send it another,
      // and both anchors have to coexist rather than one ending the other's.
      val anchorId = "$deviceId:out:${messageRequest.message.id}"

      val anchorProgressJob = if (!anchored) null else {
        val label = (messageRequest.message as? FileMessage)?.fileName ?: "file"
        runCatching { transferAnchor.begin(anchorId, label, TransferAnchor.Direction.OUTGOING) }
          .onFailure { log("Messenger", "Transfer anchor begin failed for $anchorId", it) }
        // Mirror live progress into the anchor off the same flow the callers read. Cancelled in
        // the finally below — `flow` is a hot SharedFlow that never completes on its own, so this
        // collector would otherwise keep the send coroutine alive forever.
        launch {
          flow.collect { progress ->
            if (progress is MessengerSendProgress.InProgress) {
              runCatching { transferAnchor.progress(anchorId, progress.percentage) }
            }
          }
        }
      }

      try {
        runSend(deviceId, messageRequest, flow)
      } finally {
        if (anchored) {
          anchorProgressJob?.cancel()
          runCatching { transferAnchor.end(anchorId) }
            .onFailure { log("Messenger", "Transfer anchor end failed for $anchorId", it) }
        }
      }
    }

    return flow
  }

  /**
   * The actual send. Split out of [send] so the anchor's begin/end can bracket it with a plain
   * try/finally — the body below returns early from several branches, and every one of them must
   * still release the anchor.
   */
  private suspend fun runSend(
    deviceId: String,
    messageRequest: SendMessageRequest,
    flow: MutableSharedFlow<MessengerSendProgress>,
  ) {
      flow.emit(Pending)
      log("Messenger", "Emitted Pending status for $deviceId")

      // Persist the outgoing TEXT exactly ONCE, up front, as SENDING — before any socket write,
      // any ACK, and before the device-visibility check below. This is the single row for the
      // whole logical send: handleKlardropTransfer below may retry the wire write several times,
      // but the insert happens once here, and the row is flipped to its terminal SENT/FAILED
      // state exactly once, further down, however many attempts it took (or immediately, if the
      // device isn't even visible). TextMessageHandler.handleOutgoing no longer persists anything
      // itself (see docs/connection-review.md F12/F13 — the old design inserted a fresh SENT row
      // on every retry attempt, before the write even happened). Doing this before the visibility
      // check matters: a device that drops out of the visible set between the user hitting send
      // and this coroutine running must still leave a durable, retryable row instead of silently
      // dropping the typed message (F12/F13 follow-up — a flaky-LAN dropout must not lose text).
      val originalTextMessage = messageRequest.message as? TextMessage
      // The wire id is Random.nextInt() (TextMessage.kt) with no uniqueness enforcement — stored
      // on the row for reference, but NEVER used to correlate the later SENT/FAILED flip (two
      // outgoing rows across the whole table could collide on it). The flip below is instead
      // correlated by insertMessage's returned DB row id, which is collision-free by construction.
      val pendingRowId: Long? = if (originalTextMessage != null) {
        messageRepository.insertMessage(
          remoteDeviceId = deviceId,
          content = originalTextMessage.text,
          isSender = true,
          messageType = PersistenceMessageType.TEXT,
          isRead = true,
          mimeType = "text/plain",
          messageId = originalTextMessage.id.toLong(),
          sendStatus = SendStatus.SENDING,
        )
      } else {
        null
      }

      val device = visibleDevices.getDevice(deviceId)

      // mDNS can be one-directional (a peer firewall eats our browse while the peer's dial
      // to us lands a healthy connection in the pool — the KLARDROP-JB/JD incident, where
      // VisibleDevices stayed empty for the whole session but PING/PONG flowed both ways).
      // A live pooled connection is a strictly better send path than failing here, so fall
      // through to the Klardrop transport, which picks it up out of the pool.
      val hasLivePooledConnection =
        device == null && connectionsPool.getConnection(deviceId)?.isClosed() == false

      //    skip if not visible (unless a live pooled connection exists — see above)
      if (device == null && !hasLivePooledConnection) {
        log("Messenger", "❌ Device $deviceId is not visible in device list")
        // Include what the app *did* think was visible: the 30s snapshot elsewhere is too
        // coarse to correlate with this exact failure from breadcrumbs alone.
        log(
          "Messenger",
          "Wanted to send a message to $deviceId but it is not visible " +
            "(visible ids: ${visibleDevices.visibleDevices.value.keys})"
        )
        // The user-facing string never includes the raw deviceId — that's an internal
        // identifier (random hex shortId) and is meaningless to a person. Prefer the
        // cached friendly name; fall back to a generic label so the chat error banner
        // reads like English.
        val friendlyName = visibleDevices.cachedNameFor(deviceId) ?: "Device"
        if (pendingRowId != null) {
          messageRepository.updateMessageSendStatus(pendingRowId, SendStatus.FAILED)
        }
        flow.emit(Error("$friendlyName is not visible", reason = "no-endpoints"))
        return
      }

      if (device == null) {
        log(
          "Messenger",
          "Device $deviceId is not in the visible list but a live pooled connection exists; sending over it"
        )
      } else {
        log("Messenger", "✅ Device $deviceId found in visible devices")
      }

      // Check if device is trusted and wrap message in TrustedMessage if needed.
      //
      // Skipped for FileMessage: those need to flow through the chunked-streaming path in
      // ConnectionMessenger / FileMessageHandler, which inspects the message's hasPayload
      // and special-cases FileMessage. Wrapping at this layer would replace the request
      // with a TrustedMessage envelope (hasPayload = false), short-circuiting the streaming
      // path entirely — the header would go on the wire but the payload bytes never would.
      // For FileMessage, signing happens per-frame at the wire layer in MessagesRouter via
      // sendMessageToDevice (header + each chunk independently). Result: every frame on
      // the wire is still signed for trusted peers, but ConnectionMessenger sees the real
      // FileMessage and runs the streaming dance.
      val finalMessageRequest = try {
        val message = messageRequest.message
        val isPairingMessage = message is TrustPairingRequest || message is TrustPairingResponse
        val isRevocation = message is TrustRevocationMessage
        val isFileMessage = message is FileMessage

        if (!isPairingMessage && !isRevocation && !isFileMessage && trustManager.isTrusted(deviceId)) {
          log("Messenger", "Device $deviceId is trusted, creating TrustedMessage")

          // Serialize the original message
          val messageBytes = messageSerializer.serialize(message)

          // Sign the message using TrustManager. Preserve the inner message's id on the
          // outer envelope — the receiver acks under the wire-frame id, the sender tracks
          // pending-acks under message.id, and we want those to match.
          val trustedMessage = trustManager.signMessage(messageBytes, id = message.id)

          if (trustedMessage != null) {
            log("Messenger", "Successfully created TrustedMessage for device $deviceId")
            // Create a new request with the TrustedMessage
            trustedMessage.toSimpleSendRequest()
          } else {
            log("Messenger", "Failed to create TrustedMessage for device $deviceId, sending unsigned")
            messageRequest
          }
        } else {
          if (isPairingMessage) {
            log("Messenger", "Device $deviceId: Pairing message detected, sending unsigned for protocol handshake")
          } else if (isRevocation) {
            log("Messenger", "Device $deviceId: Revocation flows unwrapped with its own embedded signature")
          } else if (isFileMessage) {
            log("Messenger", "Device $deviceId: FileMessage flows through unwrapped; per-frame wrap happens at the wire layer for the chunked path")
          } else {
            log("Messenger", "Device $deviceId is not trusted, sending unsigned request")
          }
          messageRequest
        }
      } catch (e: Exception) {
        log("Messenger", "Error creating TrustedMessage for $deviceId: ${e.message}", e)
        messageRequest // fallback to original message
      }

      // Transport preference per project policy:
      //   - Payload-bearing messages (files):  TCP > Nearby > BLE
      //     BLE is unsuitable for streaming because the macOS GATT path can't keep up
      //     with sustained writes, and Nearby is fine once the (slower) handshake is done.
      //   - Lightweight messages (text/control): TCP > BLE > Nearby
      //     For tiny chunks BLE is faster end-to-end than spinning up a Nearby session.
      // Preference is based on the *application* message shape (file vs lightweight), not
      // the trust envelope. TrustedMessage wrapping only applies to the Klardrop wire path.
      val preference = transportPreferenceFor(finalMessageRequest)
      val isKlardropControl = finalMessageRequest.message is TrustPairingRequest ||
        finalMessageRequest.message is TrustPairingResponse ||
        finalMessageRequest.message is TrustRevocationMessage
      val chosen = when {
        device != null -> preference.firstOrNull { choice ->
          if (choice == TransportChoice.KLARDROP_TCP && isKlardropControl) {
            // Nearby advertises the same unified TCP listener. Pairing frames cannot
            // go on the Nearby Share text path; Client.performDial maps Nearby→Klardrop.
            device.hasKlardropConnection() || device.hasNearbyConnection()
          } else {
            choice.isAvailable(device)
          }
        }
        // Not visible but pooled: only the Klardrop path can ride the pooled connection —
        // Nearby/BLE availability both come from the visible-map entry we don't have.
        else -> TransportChoice.KLARDROP_TCP
      }
      val transferCompleted = when (chosen) {
        TransportChoice.KLARDROP_TCP, TransportChoice.KLARDROP_BLE ->
          handleKlardropTransfer(deviceId, finalMessageRequest, flow, preferBle = chosen == TransportChoice.KLARDROP_BLE)
        // Nearby Share is not the Klardrop protocol — it only carries raw text/file bytes
        // (see NearbyClientConnectionHandler's `as TextMessage` / file paths). A TrustedMessage
        // envelope is a Klardrop wire concern and must not be handed to Nearby: that ClassCast
        // blocked trusted-device text sends in production (Bugsnag 6a4dee0c / 6a4de86e).
        TransportChoice.NEARBY -> handleNearbyTransfer(deviceId, messageRequest, flow)
        null -> {
          log("Messenger", "Wanted to send a message to $deviceId but it has no connection")
          flow.emit(Error("$deviceId but it has no connection", reason = "no-endpoints"))
          false
        }
      }

      // Terminal status for the row inserted above — exactly one flip, regardless of which
      // branch above produced the result (including the exhausted-retries and no-connection
      // paths, which is why "no connection" no longer short-circuits with an early return).
      if (pendingRowId != null) {
        messageRepository.updateMessageSendStatus(
          pendingRowId,
          if (transferCompleted) SendStatus.SENT else SendStatus.FAILED,
        )
      }

      if (transferCompleted)
        flow.emit(Completed)
  }

  override fun receive(): Flow<Pair<String, Flow<ReceiveMessageUpdate>>> { // Changed
    return messageReceiver.notifier
  }

  private suspend fun handleNearbyTransfer(
    deviceId: String,
    messageRequest: SendMessageRequest,
    sendFlow: MutableSharedFlow<MessengerSendProgress>
  ): Boolean {

    val device = visibleDevices.getDevice(deviceId)
    if (device == null) {
      log("Messenger", "Device $deviceId is not visible; nearby transfer aborted")
      sendFlow.emit(Error("Device is no longer visible"))
      return false
    }

    val nearbyConnections = device.getNearbyConnection()
    if (nearbyConnections.isEmpty()) {
      log("Messenger", "Device $deviceId has no nearby connections")
      sendFlow.emit(Error("No nearby connection available"))
      return false
    }

    // Walk every advertised endpoint until one succeeds. The Quick Share
    // service can advertise stale ports if the peer's Quick Share session
    // ended between discovery and our connect, so a "Connection refused" on
    // the first endpoint is normal — try the rest before giving up.
    var lastError: Throwable? = null
    val success = nearbyConnections.any { connection ->
      log("Messenger", "Client sending message to $deviceId: ${connection.address} ${connection.port}")
      runCatching {
        sendNearby(connection.address, connection.port, listOf(messageRequest), sendFlow)
      }.onFailure { exception ->
        lastError = exception
        log("Messenger", "Error sending message to $deviceId via ${connection.address}:${connection.port}", exception)
      }.isSuccess
    }

    if (!success) {
      val reason = lastError?.message?.takeIf { it.isNotBlank() }
        ?: "Could not reach $deviceId over Nearby Share"
      sendFlow.emit(Error(reason))
    }
    return success
  }

  private suspend fun sendNearby(
    host: String,
    port: Int,
    sendRequests: List<SendMessageRequest>,
    sendFlow: MutableSharedFlow<MessengerSendProgress>,
  ) {
    val selectorManager = SelectorManager(coroutines.ioDispatcher)
    val socket = aSocket(selectorManager).tcp().connect(InetSocketAddress(host, port))
    try {
      NearbyClientConnectionHandler(currentDeviceProvider, fileManager, sendRequests)
        .onConnection(socket, sendFlow)
    } finally {
      socket.dispose()
      // Must be closed with the socket: on Apple targets ktor's selector loop blocks in `pselect`
      // and holds one of Dispatchers.IO's 64 parallelism slots until closed, so leaking one per
      // Nearby send wedges all networking after ~64 sends.
      selectorManager.close()
    }
  }

  private suspend fun handleKlardropTransfer(
    deviceId: String,
    messageRequest: SendMessageRequest,
    flow: MutableSharedFlow<MessengerSendProgress>,
    preferBle: Boolean = false,
  ): Boolean {
    val config = ackTimeoutConfig
    val maxRetries = config.maxRetries
    var attempt = 0

    log(
      "Messenger",
      "[DEBUG] Starting handleKlardropTransfer for $deviceId, message: ${messageRequest.message.id}, maxRetries: $maxRetries"
    )

    while (attempt <= maxRetries) {
      attempt++
      log("Messenger", "[DEBUG] Attempt $attempt/$maxRetries for $deviceId, message: ${messageRequest.message.id}")

      val result = runCatching {
        // Get or establish connection. This keeps the send PENDING and waits for a connection
        // from EITHER direction (our outbound dial, or — when this peer can only be reached by
        // dialing in, e.g. the receiver sits behind a default-deny-inbound firewall — the peer's
        // inbound connection landing in the pool). The desktop's EagerReachabilityConnector dials
        // discovered-but-unconnected peers promptly, so a send issued before any connection exists
        // simply waits here until that connection appears, instead of failing fast.
        log("Messenger", "[DEBUG] Getting or establishing connection to $deviceId (attempt $attempt, preferBle=$preferBle)")
        val connectionMessenger = awaitOrEstablishConnection(deviceId, preferBle, config.connectionWaitTimeout)

        if (connectionMessenger == null) {
          log("Messenger", "[DEBUG] No connection to $deviceId within ${config.connectionWaitTimeout} (attempt $attempt)")
          // One exhausted connection-wait budget is a retryable failure, not a terminal one:
          // the loop must consume ALL configured retries before giving up (a peer's inbound
          // dial or a fresh mDNS endpoint can still land within the later attempts).
          if (attempt <= maxRetries) {
            connectionsPool.closeConnection(deviceId)
            log("Messenger", "[DEBUG] Closed connection to $deviceId, starting backoff delay")

            val delay = (1.seconds * config.retryBackoffMultiplier.pow(attempt - 1))
            log("Messenger", "[DEBUG] Waiting ${delay.inWholeMilliseconds}ms before retry (attempt $attempt)")
            withContext(coroutines.mainDispatcher) {
              kotlinx.coroutines.delay(delay)
            }

            return@runCatching false // Signal to retry
          }
          flow.emit(
            Error(
              "Could not connect to $deviceId",
              reason = "connect-failed(TimeoutCancellationException)",
            )
          )
          return false
        }

        log(
          "Messenger",
          "[DEBUG] Successfully got connection to $deviceId, sending message: ${messageRequest.message.id} (attempt $attempt)"
        )

        // Send the message - this will emit progress updates to the flow and wait for ACKs
        connectionMessenger.send(messageRequest, flow)
        log("Messenger", "[DEBUG] Successfully sent message ${messageRequest.message.id} to $deviceId (attempt $attempt)")
        true
      }.getOrElse { exception ->
        // A declined transfer is a terminal user decision, not a transport failure: don't retry
        // (that would re-prompt the recipient), and leave the connection healthy for other sends.
        if (exception is TransferRejectedException) {
          log("Messenger", "[DEBUG] Transfer to $deviceId was declined by the recipient; not retrying")
          flow.emit(Error("Recipient declined the transfer"))
          return false
        }
        log(
          "Messenger",
          "[DEBUG] Error in Klardrop transfer to $deviceId (attempt $attempt): ${exception::class.simpleName}: ${exception.message}"
        )
        // logLocal, not log: a per-attempt failure is not yet a product failure — the retry
        // below usually succeeds. Uploading here produced up to 6 Sentry events (2 per
        // attempt, with ConnectionMessenger reporting the same throwable) for one transfer
        // the user never saw fail. The terminal report is the retries-exhausted branch below,
        // so one uploaded event == one genuinely failed transfer.
        logLocal("Messenger", "[DEBUG] Full exception for attempt $attempt", exception)

        // Treat every exception caught here as transport-level and worth
        // retrying. Force a fresh redial on the next attempt by evicting the
        // pool entry so a stale half-open socket can't keep returning the
        // same error. The terminal outcome is decided by the while-loop's
        // attempt counter, not by classifying exception messages here.
        log(
          "Messenger",
          "[DEBUG] Transport-level error for $deviceId (attempt $attempt): ${exception.message}"
        )

        if (attempt <= maxRetries) {
          connectionsPool.closeConnection(deviceId)
          log("Messenger", "[DEBUG] Closed connection to $deviceId, starting backoff delay")

          val delay = (1.seconds * config.retryBackoffMultiplier.pow(attempt - 1))
          log("Messenger", "[DEBUG] Waiting ${delay.inWholeMilliseconds}ms before retry (attempt $attempt)")
          withContext(coroutines.mainDispatcher) {
            kotlinx.coroutines.delay(delay)
          }

          return@getOrElse false // Signal to retry
        } else {
          log("Messenger", "Transfer to $deviceId failed after $attempt attempts", exception)
          val errorMessage = exception.message ?: "Unknown connection error"
          flow.emit(
            Error(
              "Transfer failed: $errorMessage",
              reason = classifyTransportFailure(exception),
            )
          )
          return false
        }
      }

      if (result) {
        // Success
        log("Messenger", "[DEBUG] Successfully completed transfer to $deviceId (attempt $attempt)")
        return true
      }

      log("Messenger", "[DEBUG] Attempt $attempt failed, will retry if attempts remaining")
      // If we get here, it was a retryable failure and we should try again
    }

    // All retries exhausted
    log("Messenger", "[DEBUG] All retries exhausted for $deviceId after $maxRetries attempts")
    flow.emit(
      Error(
        "Transfer failed after $maxRetries retry attempts",
        reason = "connect-failed(TimeoutCancellationException)",
      )
    )
    return false
  }

  /**
   * Machine-readable failure class for a terminal transport exception. ACK timeouts get their
   * own class (the connection was fine; the peer just never answered) — everything else is a
   * connect/send failure tagged with the cause's class name.
   */
  private fun classifyTransportFailure(exception: Throwable): String =
    if (exception.message?.startsWith("ACK timeout", ignoreCase = true) == true) {
      "ack-timeout"
    } else {
      "connect-failed(${exception::class.simpleName})"
    }


  /**
   * Returns a usable connection to [deviceId], staying PENDING up to [timeout] for one to appear
   * from EITHER direction:
   *  - our own outbound dial (succeeds when the peer accepts inbound), and
   *  - a connection the PEER opens to us, which lands in the pool — the only path that can succeed
   *    when the peer can't be dialed (behind a default-deny-inbound firewall). That side can't be
   *    reached by us, so it dials out (its [EagerReachabilityConnector] dials discovered peers) and
   *    we ride the socket it opened.
   *
   * Each loop re-dials (fast on success; ~connect-timeout then null when the peer is firewalled),
   * then re-checks the pool for an inbound connection. So a send issued before any connection
   * exists waits here until one appears, rather than failing after a couple of quick dials.
   * Returns null only if nothing connects within [timeout].
   */
  private suspend fun awaitOrEstablishConnection(
    deviceId: String,
    preferBle: Boolean,
    timeout: Duration,
  ): ConnectionMessenger? = withTimeoutOrNull(timeout) {
    var connection: ConnectionMessenger? = null
    while (connection == null) {
      connection = getOrEstablishConnection(deviceId, preferBle)
      if (connection != null) break
      // Give the peer's inbound dial a moment to land, then re-check before re-dialing.
      kotlinx.coroutines.delay(RECONNECT_PROBE_INTERVAL)
      connection = connectionsPool.getConnection(deviceId)
    }
    connection
  }

  private suspend fun getOrEstablishConnection(
    deviceId: String,
    preferBle: Boolean = false,
  ): ConnectionMessenger? {
    log("Messenger", "[DEBUG] getOrEstablishConnection() called for $deviceId (preferBle=$preferBle)")

    // First, check if we have a valid existing connection
    val existingConnection = connectionsPool.getConnection(deviceId)
    if (existingConnection != null) {
      val isConnectionClosed = existingConnection.isClosed()
      log("Messenger", "[DEBUG] Found existing connection for $deviceId, isClosed=$isConnectionClosed")

      if (!isConnectionClosed) {
        // For files we want TCP. If the cached connection is BLE but the device is also
        // reachable via TCP — usually because BleEagerConnector raced ahead before mDNS
        // finished discovery — drop it and force a fresh TCP connect via Client.
        // For light/text messages we tolerate the cached BLE connection (matches the
        // policy: TCP > BLE > Nearby for small messages).
        val device = visibleDevices.getDevice(deviceId)
        // Pairing does not upgrade a live link. `authenticated` is decided once, during the
        // UKEY2 identity-binding exchange at connect time, so a connection dialed BEFORE the peer
        // was trusted keeps authenticated=false for its whole life. Everything fast is gated on
        // that flag — the bulk chunk path, skipping per-message signatures, skipping the
        // whole-file content hash — so "pair, then immediately send" silently ran ~14x slower
        // than the same transfer after any incidental reconnect. Recycle the link once so the
        // next dial re-runs the binding with the trust store now populated.
        // Short-circuit deliberately: the trust-store lookup suspends and hits storage, so it
        // only runs when the free checks already say an upgrade is plausible.
        val staleAuth = linkMayNeedAuthUpgrade(
          isLinkEncrypted = existingConnection.isLinkEncrypted,
          isLinkAuthenticated = existingConnection.isLinkAuthenticated,
          alreadyAttempted = deviceId in authUpgradeAttempted,
        ) && trustManager.isTrusted(deviceId)
        val tcpAvailable = device?.hasKlardropConnection() == true
        // A re-dial re-runs the UKEY2 identity binding over TCP *or* BLE (establishBleConnection
        // runs the same exchange), so any visible endpoint is a usable redial path. Gating this on
        // TCP alone left BLE-only trusted peers stuck on their pre-pairing unauthenticated link
        // forever, which is the slow path this whole branch exists to escape.
        val redialPathAvailable = device != null
        if (staleAuth && redialPathAvailable) {
          // Once per device per process: if the re-dial still comes back unauthenticated (peer's
          // stored key no longer matches, say) we must not spin re-dialling forever.
          // ponytail: plain set, benign race — worst case two devices each re-dial once.
          authUpgradeAttempted += deviceId
          log(
            "Messenger",
            "Connection for $deviceId predates pairing (authenticated=false but device is trusted); " +
              "recycling so the handshake re-binds identity"
          )
          connectionsPool.closeConnection(deviceId)
        } else if (staleAuth) {
          // Do not discard the only working link: with no discovered endpoint at all the client
          // cannot re-run the authenticated handshake, so recycling would strand the peer instead
          // of upgrading it. Keep using this encrypted link until mDNS supplies a redial path;
          // pairing responses must be able to travel over it meanwhile. The link stays
          // opportunistically encrypted but not identity-bound, so it is not MITM-proof — content
          // authenticity still holds, since TrustedMessage signs at the application layer, and
          // deviceId is deliberately NOT added to authUpgradeAttempted so the upgrade is retried
          // as soon as an endpoint appears.
          log(
            "Messenger",
            "Connection for $deviceId predates pairing, but no redial path is visible; " +
              "keeping the current link"
          )
          return existingConnection
        } else if (!preferBle && existingConnection.isBleTransport && tcpAvailable) {
          log(
            "Messenger",
            "[DEBUG] Existing connection for $deviceId is BLE but a TCP path is now available; " +
              "evicting and re-establishing over TCP"
          )
          connectionsPool.closeConnection(deviceId)
        } else {
          log("Messenger", "[DEBUG] Using existing active connection for $deviceId")
          return existingConnection
        }
      } else {
        log("Messenger", "[DEBUG] Existing connection is closed, removing and establishing new one for $deviceId")
        connectionsPool.closeConnection(deviceId)
      }
    } else {
      log("Messenger", "[DEBUG] No existing connection found for $deviceId")
    }

    // Establish a new connection
    log("Messenger", "Establishing new connection for $deviceId")
    val connectResult = runCatching {
      client.connectTo(deviceId)
    }

    if (connectResult.isFailure) {
      val exception = connectResult.exceptionOrNull()
      log("Messenger", "Failed to connect to $deviceId: ${exception?.message}")
      return null
    }

    // Verify the connection was established
    log("Messenger", "[DEBUG] Verifying new connection for $deviceId")
    val newConnection = connectionsPool.getConnection(deviceId)
    if (newConnection == null) {
      log("Messenger", "[DEBUG] Failed to establish connection for $deviceId - connection not found in pool")
      return null
    } else if (newConnection.isClosed()) {
      log("Messenger", "[DEBUG] Failed to establish connection for $deviceId - connection is closed")
      return null
    }

    log("Messenger", "[DEBUG] Successfully established new connection for $deviceId")
    return newConnection
  }

}

private enum class TransportChoice {
  KLARDROP_TCP,
  KLARDROP_BLE,
  NEARBY;

  fun isAvailable(device: com.carlom.klardrop.common.discovery.DiscoveryDevice): Boolean = when (this) {
    KLARDROP_TCP -> device.hasKlardropConnection()
    KLARDROP_BLE -> device.hasBleConnection()
    NEARBY -> device.hasNearbyConnection()
  }
}

private fun transportPreferenceFor(request: SendMessageRequest): List<TransportChoice> {
  val message = request.message
  if (message is TrustPairingRequest || message is TrustPairingResponse || message is TrustRevocationMessage) {
    return listOf(TransportChoice.KLARDROP_TCP)
  }
  // `hasPayload` distinguishes streaming/file messages from short control/text messages.
  return if (message.hasPayload) {
    // Files: TCP > Nearby > BLE. BLE can't keep up with sustained writes; Nearby is fine.
    listOf(TransportChoice.KLARDROP_TCP, TransportChoice.NEARBY, TransportChoice.KLARDROP_BLE)
  } else {
    // Text/control: TCP > BLE > Nearby. For tiny chunks BLE beats Nearby's setup cost.
    listOf(TransportChoice.KLARDROP_TCP, TransportChoice.KLARDROP_BLE, TransportChoice.NEARBY)
  }
}

/** How often, while waiting, we re-check the pool for an inbound connection before re-dialing. */
private val RECONNECT_PROBE_INTERVAL = 1.5.seconds

fun Flow<MessengerSendProgress>.untilCompleted(): Flow<MessengerSendProgress> {
  return this
    // send a closed after completed or error. this is to close the collection
    .transformWhile {
      emit(it)
      !it.isCompleted()
    }
}

/**
 * Cheap half of the "this link predates pairing" test — see the call site in
 * [MessengerImpl.getOrEstablishConnection].
 *
 * Both negative guards matter. A cleartext/BLE link is NEVER authenticated, so without
 * [isLinkEncrypted] a trusted peer on BLE would be re-dialled forever. And [alreadyAttempted]
 * bounds it to one retry per device, so a peer whose stored key no longer matches — where the
 * fresh handshake also returns unauthenticated — cannot spin.
 */
internal fun linkMayNeedAuthUpgrade(
  isLinkEncrypted: Boolean,
  isLinkAuthenticated: Boolean,
  alreadyAttempted: Boolean,
): Boolean = isLinkEncrypted && !isLinkAuthenticated && !alreadyAttempted

sealed interface MessengerSendProgress {
  data object Pending : MessengerSendProgress
  /**
   * [bytesTransferred]/[totalBytes] are the raw byte counters behind [percentage]. The UI needs
   * them (not the rounded percent) to derive throughput and ETA — a 5%-quantised percentage over
   * a multi-GB file is far too coarse to differentiate for a rate. Both default to 0 for
   * non-file sends, which have no meaningful byte count.
   */
  data class InProgress(
    val percentage: Int,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
  ) : MessengerSendProgress

  /**
   * The file header is on the wire and we're blocked on the recipient's ACK_READY — which for
   * an untrusted peer means a human has to tap Accept. No bytes flow during this window, so a
   * percentage would be a lie; the UI shows an indeterminate bar instead of a dead 0%.
   *
   * Not terminal: [isCompleted] stays false so `untilCompleted()` keeps collecting.
   */
  data object AwaitingRecipient : MessengerSendProgress
  data object Completed : MessengerSendProgress

  /**
   * [message] is the human-readable text; [reason] is the machine-readable failure class for
   * callers that need to distinguish outcomes (pairing UI surfaces them differently):
   *   - "no-endpoints" — device not visible and no pooled connection
   *   - "connect-failed(<cause class>)" — endpoints existed but every dial/send failed
   *   - "ack-timeout" — connected, but the peer never acknowledged
   * Null for legacy failure sites that have no classified cause.
   */
  data class Error(val message: String = "", val reason: String? = null) : MessengerSendProgress

  fun isCompleted(): Boolean = this is Completed || this is Error
}
