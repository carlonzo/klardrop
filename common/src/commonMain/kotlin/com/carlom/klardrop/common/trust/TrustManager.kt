package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Manages trust relationships between devices.
 * Pure domain component focused on trust logic, cryptography, and state management.
 * Does not handle communication - that's delegated to PairingProtocolCoordinator.
 */
class TrustManager(
  private val crypto: TrustCrypto,
  private val storage: TrustStorage,
  private val clock: Clock,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val nonceManager: NonceManager = NonceManager()
) {

  companion object {
    private const val PAIRING_TIMEOUT_SECONDS = 30
    private const val MAX_TIME_DIFF_SECONDS = 300 // 5 minutes

    /**
     * HKDF info string for deriving the per-pair file-chunk HMAC key. Versioned so we
     * can rotate the derivation later without colliding with existing deployed keys.
     */
    private val CHUNK_MAC_INFO = "klardrop-chunk-mac-v1".encodeToByteArray()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // Temporary storage for in-progress pairing sessions
  private val pairingSessions = mutableMapOf<String, PairingSession>()

  // ECDSA keypair for this device (for signing messages)
  private var deviceECDSAKeys: TrustCrypto.ECDSAKeyPair? = null

  // Callback for UI approval dialogs
  private var pairingApprovalCallback: PairingApprovalCallback? = null

  // Events for pairing operations that external coordinators can listen to
  private val _pairingEvents = MutableSharedFlow<PairingEvent>(extraBufferCapacity = 10)
  val pairingEvents: SharedFlow<PairingEvent> = _pairingEvents.asSharedFlow()

  /**
   * Initialize the trust manager and load or generate device signing keys.
   * Device identity persists across app restarts so peers we've paired with can keep
   * verifying our signatures using the public key we published to them at pairing time.
   *
   * Pre-fix this method silently regenerated a brand-new keypair every restart (with a
   * comment claiming public-key derivation wasn't supported), which rotated the device
   * identity behind the user's back and broke every existing pairing. The actual fix is
   * to persist BOTH halves of the keypair (private + public) and reconstruct from those
   * bytes on subsequent runs — not generate.
   */
  suspend fun initialize() {
    if (deviceECDSAKeys != null) return

    val existingPrivate = storage.getDevicePrivateKey()
    val existingPublic = storage.getDevicePublicKey()

    if (existingPrivate != null && existingPublic != null) {
      deviceECDSAKeys = TrustCrypto.ECDSAKeyPair(
        publicKey = TrustCrypto.ECDSAPublicKey(existingPublic),
        privateKey = TrustCrypto.ECDSAPrivateKey(existingPrivate),
      )
      log("🔐 TrustManager", "Loaded persisted device identity")
      return
    }

    // Either a fresh install, or a legacy install that only persisted the private key
    // (no way to recover the matching public key from the RAW scalar in this lib). Either
    // way we have to generate; persist BOTH halves so the next restart can reconstruct
    // without rotating identity again.
    val isLegacyMigration = existingPrivate != null
    val fresh = crypto.generateECDSAKeyPair()
    deviceECDSAKeys = fresh
    storage.storeDevicePrivateKey(fresh.privateKey.data)
    storage.storeDevicePublicKey(fresh.publicKey.data)

    if (isLegacyMigration) {
      // Pre-fix builds rotated the keypair on every restart. Any peer paired with us
      // therefore cached an obsolete public key — every signature we now produce will
      // fail their verification. Wipe local trust so the user re-pairs cleanly; the
      // peer's trust DB also gets reset on their first launch with the fix, so this
      // recovery is symmetric.
      storage.clearAllTrustedDevices()
      log(
        "🔐 TrustManager",
        "Generated new device identity and reset local trust (legacy storage missing public key — peers must re-pair)",
      )
    } else {
      log("🔐 TrustManager", "Generated and stored new device identity (no persisted identity)")
    }
  }

  /**
   * Create a pairing request for a target device.
   * Returns the request data to be sent by PairingProtocolCoordinator.
   * @param targetDeviceId Device ID to pair with
   * @return Result containing the TrustPairingRequest or failure
   */
  suspend fun createPairingRequest(targetDeviceId: String): Result<TrustPairingRequest> = withContext(Dispatchers.Default) {
    log("🔐 TrustManager", " createPairingRequest() called for deviceId: $targetDeviceId")
    return@withContext try {
      val currentDevice = currentDeviceProvider.get()
      log("🔐 TrustManager", " Current device: ${currentDevice.shortDeviceId} (${currentDevice.deviceName})")

      // Ensure we have ECDSA keys
      initialize()
      val ecdsaKeys = deviceECDSAKeys ?: return@withContext Result.failure(Exception("ECDSA keys not initialized"))
      log("🔐 TrustManager", " ECDSA keys initialized")

      // Generate ECDH keypair for this pairing session
      val ecdhKeyPair = crypto.generateECDHKeyPair()
      val ecdhPublicKeyBytes = crypto.encodePublicKey(ecdhKeyPair.publicKey)
      val ecdsaPublicKeyBytes = crypto.encodeECDSAPublicKey(ecdsaKeys.publicKey)
      log("🔐 TrustManager", " Generated ECDH keypair, public key size: ${ecdhPublicKeyBytes.size} bytes")

      // Store session for completion when response arrives
      val session = PairingSession(
        targetDeviceId = targetDeviceId,
        ecdhKeyPair = ecdhKeyPair,
        timestamp = clock.currentTimeMillis()
      )
      pairingSessions[targetDeviceId] = session
      log("🔐 TrustManager", " Stored pairing session for device: $targetDeviceId")

      // Create pairing request
      val request = TrustPairingRequest(
        deviceId = currentDevice.shortDeviceId,
        deviceName = currentDevice.deviceName,
        ecdhPublicKey = ecdhPublicKeyBytes,
        ecdsaPublicKey = ecdsaPublicKeyBytes,
        timestamp = clock.currentTimeMillis(),
        deviceType = currentDevice.deviceType.name,
        appVersion = "1.0.0" // TODO: Get from build config
      )
      log("🔐 TrustManager", " Created TrustPairingRequest: ${request.deviceId} -> $targetDeviceId")

      // Set timeout to clean up session
      scope.launch {
        delay(PAIRING_TIMEOUT_SECONDS.seconds)
        if (pairingSessions[targetDeviceId] == session) {
          pairingSessions.remove(targetDeviceId)
          log("🔐 TrustManager", " Cleaned up pairing session for $targetDeviceId after timeout")
        }
      }

      Result.success(request)
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Called when a pairing request has been successfully sent.
   * Used for tracking and logging purposes.
   */
  fun onPairingRequestSent(targetDeviceId: String) {
    log("🔐 TrustManager", " Pairing request confirmed sent to $targetDeviceId")
  }

  /**
   * Called when a pairing request failed to send.
   * Cleans up the pairing session.
   */
  fun onPairingRequestFailed(targetDeviceId: String) {
    log("🔐 TrustManager", " Pairing request failed for $targetDeviceId, cleaning up session")
    pairingSessions.remove(targetDeviceId)
  }

  /**
   * Handle incoming pairing request from another device.
   * Validates the request and emits an event for external coordinators.
   */
  suspend fun handleIncomingPairingRequest(
    request: TrustPairingRequest,
    senderAddress: String
  ) = withContext(Dispatchers.Default) {
    log("🔐 TrustManager", " handleIncomingPairingRequest() called from ${request.deviceName} (${request.deviceId})")

    // Validate timestamp to prevent replay attacks
    val currentTime = clock.currentTimeMillis()
    val timeDiff = kotlin.math.abs(currentTime - request.timestamp)
    log("🔐 TrustManager", " Time validation: current=$currentTime, request=${request.timestamp}, diff=${timeDiff}ms")

    if (timeDiff > MAX_TIME_DIFF_SECONDS * 1000) {
      log("🔐 TrustManager", " ❌ Rejecting request due to timestamp too old (>${MAX_TIME_DIFF_SECONDS}s)")
      return@withContext // Ignore old requests
    }

    log("🔐 TrustManager", " Timestamp validation passed")

    // Create decision object with callback
    val callback = pairingApprovalCallback
    val decision = if (callback != null) {
      log("🔐 TrustManager", " ✅ Creating pairing decision for device: ${request.deviceName}")
      PairingDecision(
        deviceId = request.deviceId,
        deviceName = request.deviceName,
        deviceType = request.deviceType,
        approvalCallback = callback
      )
    } else {
      log("🔐 TrustManager", " ❌ CRITICAL: pairingApprovalCallback is null! No UI dialog will be shown")
      null
    }

    // Emit event for external coordinators to handle
    _pairingEvents.tryEmit(PairingEvent.PairingRequestReceived(request, senderAddress, decision))
  }

  /**
   * Process incoming pairing request from another device.
   * Validates the request and returns a decision object for approval.
   * @return PairingDecision if request is valid, null if invalid/expired
   */
  suspend fun processPairingRequest(
    request: TrustPairingRequest,
    senderAddress: String
  ): PairingDecision? = withContext(Dispatchers.Default) {
    log("🔐 TrustManager", " processPairingRequest() called from ${request.deviceName} (${request.deviceId})")

    // Validate timestamp to prevent replay attacks
    val currentTime = clock.currentTimeMillis()
    val timeDiff = kotlin.math.abs(currentTime - request.timestamp)
    log("🔐 TrustManager", " Time validation: current=$currentTime, request=${request.timestamp}, diff=${timeDiff}ms")

    if (timeDiff > MAX_TIME_DIFF_SECONDS * 1000) {
      log("🔐 TrustManager", " ❌ Rejecting request due to timestamp too old (>${MAX_TIME_DIFF_SECONDS}s)")
      return@withContext null // Ignore old requests
    }

    log("🔐 TrustManager", " Timestamp validation passed")

    // Return decision object with callback
    val callback = pairingApprovalCallback
    if (callback == null) {
      log("🔐 TrustManager", " ❌ CRITICAL: pairingApprovalCallback is null! No UI dialog will be shown")
      return@withContext null
    }

    log("🔐 TrustManager", " ✅ Creating pairing decision for device: ${request.deviceName}")
    PairingDecision(
      deviceId = request.deviceId,
      deviceName = request.deviceName,
      deviceType = request.deviceType,
      approvalCallback = callback
    )
  }

  /**
   * Create an acceptance response for a pairing request.
   * Generates keys and stores trust relationship.
   * @return Result containing the TrustPairingResponse or failure
   */
  suspend fun createPairingAcceptance(
    request: TrustPairingRequest
  ): Result<TrustPairingResponse> = withContext(Dispatchers.Default) {
    try {
      val currentDevice = currentDeviceProvider.get()

      // Ensure we have ECDSA keys
      initialize()
      val ecdsaKeys = deviceECDSAKeys ?: return@withContext Result.failure(Exception("ECDSA keys not initialized"))

      // Generate our ECDH keypair
      val ourEcdhKeyPair = crypto.generateECDHKeyPair()
      val ourEcdhPublicKeyBytes = crypto.encodePublicKey(ourEcdhKeyPair.publicKey)
      val ourEcdsaPublicKeyBytes = crypto.encodeECDSAPublicKey(ecdsaKeys.publicKey)

      // Compute the ECDH shared secret from our private key + peer's public key. Both
      // sides arrive at the same 32 bytes. Persist it: the file-transfer path derives an
      // HMAC key from this secret to authenticate individual chunks ~1000× faster than
      // per-chunk ECDSA would.
      val sharedSecret = crypto.computeECDHSecret(
        privateKey = ourEcdhKeyPair.privateKey,
        peerPublicKeyBytes = request.ecdhPublicKey,
      )

      // Store peer's keys for future use
      storage.storeTrustedDevice(request.deviceId, request.ecdhPublicKey)  // ECDH key
      storage.storeECDSAKey(request.deviceId, request.ecdsaPublicKey)  // ECDSA key for verification
      storage.storeSharedSecret(request.deviceId, sharedSecret)

      // Create response
      val response = TrustPairingResponse(
        deviceId = currentDevice.shortDeviceId,
        deviceName = currentDevice.deviceName,
        ecdhPublicKey = ourEcdhPublicKeyBytes,
        ecdsaPublicKey = ourEcdsaPublicKeyBytes,
        accepted = true,
        timestamp = clock.currentTimeMillis()
      )

      log("🔐 TrustManager", " ✅ Created TrustPairingResponse (accepted) for ${request.deviceId}")
      Result.success(response)
    } catch (e: Exception) {
      log("🔐 TrustManager", " ❌ Failed to create acceptance: ${e.message}")
      Result.failure(e)
    }
  }

  /**
   * Create a rejection response for a pairing request.
   * @return TrustPairingResponse with rejection
   */
  suspend fun createPairingRejection(deviceId: String): TrustPairingResponse = withContext(Dispatchers.Default) {
    val currentDevice = currentDeviceProvider.get()
    TrustPairingResponse(
      deviceId = currentDevice.shortDeviceId,
      deviceName = currentDevice.deviceName,
      ecdhPublicKey = ByteArray(0), // Empty for rejection
      ecdsaPublicKey = ByteArray(0), // Empty for rejection
      accepted = false,
      timestamp = clock.currentTimeMillis(),
      rejectionReason = "User declined"
    )
  }

  /**
   * Finalize pairing after receiving response from target device.
   * Completes the key exchange and stores trust relationship.
   */
  suspend fun finalizePairing(response: TrustPairingResponse) = withContext(Dispatchers.Default) {
    log(
      "🔐 TrustManager",
      " finalizePairing() called for device: ${response.deviceId} (${response.deviceName}), accepted: ${response.accepted}"
    )

    val session = pairingSessions[response.deviceId]
    if (session == null) {
      log("🔐 TrustManager", " ❌ No pairing session found for device: ${response.deviceId}")
      return@withContext
    }

    log("🔐 TrustManager", " Found pairing session for device: ${response.deviceId}")

    try {
      if (!response.accepted) {
        log("🔐 TrustManager", " ❌ Pairing was rejected by device: ${response.deviceId}")

        // Emit failure event for UI updates
        _pairingEvents.tryEmit(PairingEvent.PairingCompleted(response.deviceId, response.deviceName, false))

        // TODO: Show rejection message to user
        return@withContext
      }

      log("🔐 TrustManager", " Computed shared secret, storing trusted device keys...")

      // Compute the ECDH shared secret on this side too. The acceptor computed the same
      // value during createPairingAcceptance — both sides now hold matching 32 bytes that
      // never appeared on the wire. Persist it for fast HMAC over file chunks later.
      val sharedSecret = crypto.computeECDHSecret(
        privateKey = session.ecdhKeyPair.privateKey,
        peerPublicKeyBytes = response.ecdhPublicKey,
      )

      // Store peer's keys
      storage.storeTrustedDevice(response.deviceId, response.ecdhPublicKey)  // ECDH key
      storage.storeECDSAKey(response.deviceId, response.ecdsaPublicKey)  // ECDSA key for verification
      storage.storeSharedSecret(response.deviceId, sharedSecret)

      // DIAGNOSTIC: Log what public key we're storing for the peer
      log("🔐 TrustManager", " Device ${response.deviceId} is now trusted")

      // Emit completion event for UI updates
      _pairingEvents.tryEmit(PairingEvent.PairingCompleted(response.deviceId, response.deviceName, true))

      // TODO: Show success message to user

    } catch (e: Exception) {
      log("🔐 TrustManager", " ❌ Exception during finalizePairing: ${e.message}")

      // Emit failure event for UI updates
      _pairingEvents.tryEmit(PairingEvent.PairingCompleted(response.deviceId, response.deviceName, false))

      // TODO: Show error message to user
    } finally {
      // Clean up session
      pairingSessions.remove(response.deviceId)
      log("🔐 TrustManager", " Cleaned up pairing session for device: ${response.deviceId}")
    }
  }

  /**
   * Check if a device is trusted.
   */
  suspend fun isTrusted(deviceId: String): Boolean {
    return storage.isTrusted(deviceId)
  }

  /**
   * Get list of all trusted devices.
   */
  suspend fun getTrustedDevices(): List<TrustedDevice> {
    return storage.getAllTrustedDevices()
      .map { (deviceId, publicKey) ->
        TrustedDevice(deviceId, publicKey)
      }
  }

  /**
   * Remove trust relationship with a device.
   */
  suspend fun removeTrust(deviceId: String) {
    storage.removeTrustedDevice(deviceId)
  }

  /**
   * Sign a message with this device's ECDSA private key.
   *
   * @param message Message bytes to sign
   * @param id Optional id to assign to the resulting [TrustedMessage]. When the wrap is
   * being applied at the wire level around an existing application message, callers should
   * pass the inner message's id so the wire-frame id (which the sender's ConnectionMessenger
   * tracks pending-ACKs under, and which the receiver's MessagesRouter echoes back in
   * acks) matches what the application layer expects. Defaults to a fresh random id for
   * standalone signed payloads.
   */
  suspend fun signMessage(message: ByteArray, id: Int = kotlin.random.Random.nextInt()): TrustedMessage? {
    // Lazily ensure device identity is loaded. Pre-fix, only the pairing-creation paths
    // called initialize(), so on a fresh app start the very first signMessage (typically
    // a clipboard sync or a file send to an already-paired device) would see
    // deviceECDSAKeys == null, return null, and the caller would fall back to sending the
    // message UNSIGNED — which the trusted peer's security gate then rejected. Calling
    // initialize() here makes signing a self-contained operation that just works.
    initialize()
    val signingKeys = deviceECDSAKeys ?: return null

    return try {
      val timestamp = clock.currentTimeMillis()
      val nonce = crypto.generateNonce()
      val currentDevice = currentDeviceProvider.get()

      // Create data to sign
      val dataToSign = crypto.combineForSigning(message, timestamp, nonce)

      // Sign data with ECDSA
      val signature = crypto.signWithECDSA(signingKeys.privateKey, dataToSign)

      // DIAGNOSTIC: Log what keys we're using for signing
      log("🔐 TrustManager", " DIAGNOSTIC: Signing message with device ${currentDevice.shortDeviceId}")

      TrustedMessage(
        payload = message,
        timestamp = timestamp,
        nonce = nonce,
        signature = signature,
        senderId = currentDevice.shortDeviceId,
        id = id,
      )
    } catch (e: Exception) {
      log("🔐 TrustManager", "Failed to sign message ", e)
      null
    }
  }

  /**
   * Verify a signed message from a trusted device.
   * Implements timing attack protection and nonce replay prevention.
   * @param trustedMessage Message to verify
   * @return true if signature is valid and from trusted device
   */
  suspend fun verifyMessage(trustedMessage: TrustedMessage): Boolean {
    try {
      // Get sender's ECDSA public key for verification
      val senderECDSAKey = storage.getECDSAKey(trustedMessage.senderId)
        ?: return false

      // --- Perform all checks and store results to avoid timing attacks ---
      log("🔐 TrustManager", "Verifying message from ${trustedMessage.senderId}")

      // 1. Timestamp check
      val currentTime = clock.currentTimeMillis()
      val isTimestampValid = kotlin.math.abs(currentTime - trustedMessage.timestamp) <= MAX_TIME_DIFF_SECONDS * 1000
      log("🔐 TrustManager", "Timestamp validation: $isTimestampValid")

      // 2. Nonce check - prevents replay attacks
      val isNonceValid = nonceManager.isNonceValid(trustedMessage.senderId, trustedMessage.nonce)
      log("🔐 TrustManager", "Nonce validation: $isNonceValid")

      // 3. Signature check (The expensive part)
      // We perform this even if other checks fail to keep timing consistent
      val dataToVerify = crypto.combineForSigning(
        trustedMessage.payload,
        trustedMessage.timestamp,
        trustedMessage.nonce
      )
      val isSignatureValid = crypto.verifyECDSA(senderECDSAKey, dataToVerify, trustedMessage.signature)
      log("🔐 TrustManager", "Signature validation: $isSignatureValid")

      // Use bitwise 'and' to prevent short-circuiting
      // All three booleans are evaluated before the final result is determined
      return isTimestampValid and isNonceValid and isSignatureValid

    } catch (e: Exception) {
      // Log internal error for debugging but return generic failure
      log("🔐 TrustManager", "Internal error during message verification", e)
      return false
    }
  }

  /**
   * Set callback for pairing approval dialogs.
   */
  fun setPairingApprovalCallback(callback: PairingApprovalCallback) {
    this.pairingApprovalCallback = callback
  }

  /**
   * Per-peer MAC key derived from the ECDH shared secret stored at pairing time.
   * Returns null when no shared secret is on file — typically legacy pairings from before
   * we persisted it; callers fall back to per-frame ECDSA signing in that case.
   *
   * Both peers derive the same 32-byte key because they hold the same shared secret and
   * use the same HKDF salt + info string.
   */
  suspend fun macKeyFor(deviceId: String): ByteArray? {
    val sharedSecret = storage.getSharedSecret(deviceId) ?: return null
    return crypto.hkdfSha256(
      sharedSecret = sharedSecret,
      info = CHUNK_MAC_INFO,
    )
  }

  /**
   * Compute an HMAC-SHA256 tag for one outgoing file chunk. The MAC input binds the chunk
   * payload to its position in the transfer (fileMessageId, seq, isLast) so an attacker
   * can't replay a chunk into another transfer or reorder chunks within one.
   *
   * Returns null when there's no shared secret with [deviceId] — the chunked-send path
   * then writes the chunk unwrapped, falling back to whatever the receiver's contentHash
   * check will catch at completion time.
   */
  suspend fun computeChunkMac(
    deviceId: String,
    fileMessageId: Int,
    seq: Int,
    isLast: Boolean,
    data: ByteArray,
  ): ByteArray? {
    val key = macKeyFor(deviceId) ?: return null
    return crypto.hmacSha256(key, chunkMacInput(fileMessageId, seq, isLast, data))
  }

  /** Verify [tag] over the same framed input that the sender produced. */
  suspend fun verifyChunkMac(
    deviceId: String,
    fileMessageId: Int,
    seq: Int,
    isLast: Boolean,
    data: ByteArray,
    tag: ByteArray,
  ): Boolean {
    val key = macKeyFor(deviceId) ?: return false
    return crypto.verifyHmacSha256(key, chunkMacInput(fileMessageId, seq, isLast, data), tag)
  }

  private fun chunkMacInput(
    fileMessageId: Int,
    seq: Int,
    isLast: Boolean,
    data: ByteArray,
  ): ByteArray {
    // Layout: 4-byte fileMessageId BE || 4-byte seq BE || 1-byte isLast || data.
    // Fixed-width framing fields make the MAC input unambiguous across implementations.
    val out = ByteArray(4 + 4 + 1 + data.size)
    out[0] = (fileMessageId ushr 24).toByte()
    out[1] = (fileMessageId ushr 16).toByte()
    out[2] = (fileMessageId ushr 8).toByte()
    out[3] = fileMessageId.toByte()
    out[4] = (seq ushr 24).toByte()
    out[5] = (seq ushr 16).toByte()
    out[6] = (seq ushr 8).toByte()
    out[7] = seq.toByte()
    out[8] = if (isLast) 1 else 0
    data.copyInto(out, 9)
    return out
  }

}

/**
 * Represents a trusted device.
 */
data class TrustedDevice(
  val deviceId: String,
  val publicKey: ByteArray
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TrustedDevice) return false
    if (deviceId != other.deviceId) return false
    if (!publicKey.contentEquals(other.publicKey)) return false
    return true
  }

  override fun hashCode(): Int {
    var result = deviceId.hashCode()
    result = 31 * result + publicKey.contentHashCode()
    return result
  }
}

/**
 * Events emitted by TrustManager that external coordinators can listen to.
 */
sealed interface PairingEvent {
  data class SendPairingRequest(val targetDeviceId: String, val request: TrustPairingRequest) : PairingEvent
  data class SendPairingResponse(val targetDeviceId: String, val response: TrustPairingResponse) : PairingEvent
  data class PairingRequestReceived(val request: TrustPairingRequest, val senderAddress: String, val decision: PairingDecision?) :
    PairingEvent

  data class PairingCompleted(val deviceId: String, val deviceName: String, val success: Boolean) : PairingEvent
}

/**
 * Represents a pairing decision that needs user approval.
 * Encapsulates the approval callback mechanism.
 */
data class PairingDecision(
  val deviceId: String,
  val deviceName: String,
  val deviceType: String,
  private val approvalCallback: PairingApprovalCallback?
) {
  fun showApprovalDialog(onAccept: suspend () -> Unit, onReject: suspend () -> Unit) {
    approvalCallback?.onPairingRequested(
      deviceId = deviceId,
      deviceName = deviceName,
      deviceType = deviceType,
      onAccept = onAccept,
      onReject = onReject
    )
  }
}

/**
 * Callback interface for pairing approval UI.
 */
interface PairingApprovalCallback {
  fun onPairingRequested(
    deviceId: String,
    deviceName: String,
    deviceType: String,
    onAccept: suspend () -> Unit,
    onReject: suspend () -> Unit
  )
}

/**
 * Internal class to track pairing sessions.
 */
private data class PairingSession(
  val targetDeviceId: String,
  val ecdhKeyPair: TrustCrypto.ECDHKeyPair,
  val timestamp: Long
)