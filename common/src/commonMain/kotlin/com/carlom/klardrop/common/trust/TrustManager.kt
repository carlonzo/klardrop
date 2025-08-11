package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustedMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.utils.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

/**
 * Manages trust relationships between devices.
 * Orchestrates pairing flow, message signing/verification, and trust state management.
 */
class TrustManager(
  private val crypto: TrustCrypto,
  private val storage: TrustStorage,
  private val clock: Clock,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val messenger: Messenger
) {

  companion object {
    private const val PAIRING_TIMEOUT_SECONDS = 30
    private const val MAX_TIME_DIFF_SECONDS = 300 // 5 minutes
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // Temporary storage for in-progress pairing sessions
  private val pairingSessions = mutableMapOf<String, PairingSession>()

  // ECDSA keypair for this device (for signing messages)
  private var deviceECDSAKeys: TrustCrypto.ECDSAKeyPair? = null

  // Callback for UI approval dialogs
  private var pairingApprovalCallback: PairingApprovalCallback? = null

  /**
   * Initialize the trust manager and generate device signing keys.
   */
  suspend fun initialize() {
    if (deviceECDSAKeys == null) {
      deviceECDSAKeys = crypto.generateECDSAKeyPair()
      // TODO: Persist device ECDSA keys for consistency across restarts
    }
  }

  /**
   * Initiate pairing with a target device.
   * @param targetDeviceId Device ID to pair with
   * @return Result indicating success or failure
   */
  suspend fun initiatePairing(targetDeviceId: String): Result<Unit> = withContext(Dispatchers.Default) {
    println("🔐 [TrustManager] initiatePairing() called for deviceId: $targetDeviceId")
    return@withContext try {
      val currentDevice = currentDeviceProvider.get()
      println("🔐 [TrustManager] Current device: ${currentDevice.shortDeviceId} (${currentDevice.deviceName})")

      // Ensure we have ECDSA keys
      initialize()
      val ecdsaKeys = deviceECDSAKeys ?: return@withContext Result.failure(Exception("ECDSA keys not initialized"))
      println("🔐 [TrustManager] ECDSA keys initialized: ${ecdsaKeys != null}")

      // Generate ECDH keypair for this pairing session
      val ecdhKeyPair = crypto.generateECDHKeyPair()
      val ecdhPublicKeyBytes = crypto.encodePublicKey(ecdhKeyPair.publicKey)
      val ecdsaPublicKeyBytes = crypto.encodeECDSAPublicKey(ecdsaKeys.publicKey)
      println("🔐 [TrustManager] Generated ECDH keypair, public key size: ${ecdhPublicKeyBytes.size} bytes")

      // Store session for completion when response arrives
      val session = PairingSession(
        targetDeviceId = targetDeviceId,
        ecdhKeyPair = ecdhKeyPair,
        timestamp = clock.currentTimeMillis()
      )
      pairingSessions[targetDeviceId] = session
      println("🔐 [TrustManager] Stored pairing session for device: $targetDeviceId")

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
      println("🔐 [TrustManager] Created TrustPairingRequest: ${request.deviceId} -> $targetDeviceId")

      // Set timeout to clean up session
      scope.launch {
        delay(PAIRING_TIMEOUT_SECONDS.seconds)
        pairingSessions.remove(targetDeviceId)
        println("🔐 [TrustManager] Cleaned up pairing session for $targetDeviceId after timeout")
      }

      // Send message through communication layer

      println("🔐 [TrustManager] ✅ Messenger available, sending TrustPairingRequest to $targetDeviceId")

      // Send the pairing request and wait for result
      try {
        val sendResult = messenger.send(targetDeviceId, request.toSimpleSendRequest())
          .first { it.isCompleted() }

        when (sendResult) {
          is MessengerSendProgress.Completed -> {
            println("🔐 [TrustManager] ✅ SUCCESS: TrustPairingRequest sent successfully to $targetDeviceId")
            Result.success(Unit)
          }

          is MessengerSendProgress.Error -> {
            println("🔐 [TrustManager] ❌ FAILED: TrustPairingRequest send failed: ${sendResult.message}")
            pairingSessions.remove(targetDeviceId) // Cleanup on failure
            Result.failure(Exception("Failed to send pairing request: ${sendResult.message}"))
          }

          else -> {
            println("🔐 [TrustManager] ❌ UNEXPECTED: Send result was neither Completed nor Error: $sendResult")
            pairingSessions.remove(targetDeviceId) // Cleanup on failure
            Result.failure(Exception("Unexpected send result: $sendResult"))
          }
        }
      } catch (e: Exception) {
        println("🔐 [TrustManager] ❌ EXCEPTION: Failed to send pairing request: ${e.message}")
        pairingSessions.remove(targetDeviceId) // Cleanup on failure
        Result.failure(e)
      }

    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  /**
   * Handle incoming pairing request from another device.
   * Shows approval dialog to user.
   */
  suspend fun handlePairingRequest(
    request: TrustPairingRequest,
    senderAddress: String
  ) = withContext(Dispatchers.Default) {
    println("🔐 [TrustManager] handlePairingRequest() called from ${request.deviceName} (${request.deviceId})")

    // Validate timestamp to prevent replay attacks
    val currentTime = clock.currentTimeMillis()
    val timeDiff = kotlin.math.abs(currentTime - request.timestamp)
    println("🔐 [TrustManager] Time validation: current=$currentTime, request=${request.timestamp}, diff=${timeDiff}ms")

    if (timeDiff > MAX_TIME_DIFF_SECONDS * 1000) {
      println("🔐 [TrustManager] ❌ Rejecting request due to timestamp too old (>${MAX_TIME_DIFF_SECONDS}s)")
      return@withContext // Ignore old requests
    }

    println("🔐 [TrustManager] Timestamp validation passed, checking pairing approval callback")

    // Show approval dialog to user
    val callback = pairingApprovalCallback
    if (callback == null) {
      println("🔐 [TrustManager] ❌ CRITICAL: pairingApprovalCallback is null! No UI dialog will be shown")
      return@withContext
    }

    println("🔐 [TrustManager] ✅ Showing pairing approval dialog to user for device: ${request.deviceName}")
    callback.onPairingRequested(
      deviceId = request.deviceId,
      deviceName = request.deviceName,
      deviceType = request.deviceType,
      onAccept = {
        println("🔐 [TrustManager] User accepted pairing with ${request.deviceName}")
        acceptPairing(request, senderAddress)
      },
      onReject = {
        println("🔐 [TrustManager] User rejected pairing with ${request.deviceName}")
        rejectPairing(request.deviceId)
      }
    )
  }

  /**
   * Accept a pairing request and complete the key exchange.
   */
  private suspend fun acceptPairing(
    request: TrustPairingRequest,
    senderAddress: String
  ) = withContext(Dispatchers.Default) {
    try {
      val currentDevice = currentDeviceProvider.get()

      // Ensure we have ECDSA keys
      initialize()
      val ecdsaKeys = deviceECDSAKeys ?: return@withContext

      // Generate our ECDH keypair
      val ourEcdhKeyPair = crypto.generateECDHKeyPair()
      val ourEcdhPublicKeyBytes = crypto.encodePublicKey(ourEcdhKeyPair.publicKey)
      val ourEcdsaPublicKeyBytes = crypto.encodeECDSAPublicKey(ecdsaKeys.publicKey)

      // Compute shared secret (though we won't use it for now, just store public keys)
      val sharedSecret = crypto.computeECDHSecret(
        privateKey = ourEcdhKeyPair.privateKey,
        peerPublicKeyBytes = request.ecdhPublicKey
      )

      // Store peer's keys for future use
      storage.storeTrustedDevice(request.deviceId, request.ecdhPublicKey)  // ECDH key
      storage.storeECDSAKey(request.deviceId, request.ecdsaPublicKey)  // ECDSA key for verification

      // Send response
      val response = TrustPairingResponse(
        deviceId = currentDevice.shortDeviceId,
        deviceName = currentDevice.deviceName,
        ecdhPublicKey = ourEcdhPublicKeyBytes,
        ecdsaPublicKey = ourEcdsaPublicKeyBytes,
        accepted = true,
        timestamp = clock.currentTimeMillis()
      )

      println("🔐 [TrustManager] ✅ Sending TrustPairingResponse (accepted) to ${request.deviceId}")
      scope.launch {
        try {
          val sendResult = messenger.send(request.deviceId, response.toSimpleSendRequest())
            .first { it.isCompleted() }

          when (sendResult) {
            is MessengerSendProgress.Completed -> {
              println("🔐 [TrustManager] ✅ SUCCESS: Pairing acceptance sent to ${request.deviceId}")
            }

            is MessengerSendProgress.Error -> {
              println("🔐 [TrustManager] ❌ FAILED: Could not send pairing acceptance: ${sendResult.message}")
            }

            else -> {
              println("🔐 [TrustManager] ❌ UNEXPECTED: Pairing acceptance send result: $sendResult")
            }
          }
        } catch (e: Exception) {
          println("🔐 [TrustManager] ❌ EXCEPTION: Failed to send pairing acceptance: ${e.message}")
        }
      }

    } catch (e: Exception) {
      // TODO: Log error and send rejection
      rejectPairing(request.deviceId)
    }
  }

  /**
   * Reject a pairing request.
   */
  private suspend fun rejectPairing(deviceId: String) {
    val currentDevice = currentDeviceProvider.get()
    val response = TrustPairingResponse(
      deviceId = currentDevice.shortDeviceId,
      deviceName = currentDevice.deviceName,
      ecdhPublicKey = ByteArray(0), // Empty for rejection
      ecdsaPublicKey = ByteArray(0), // Empty for rejection
      accepted = false,
      timestamp = clock.currentTimeMillis(),
      rejectionReason = "User declined"
    )

    println("🔐 [TrustManager] ✅ Sending TrustPairingResponse (rejected) to $deviceId")
    scope.launch {
      try {
        val sendResult = messenger.send(deviceId, response.toSimpleSendRequest())
          .first { it.isCompleted() }

        when (sendResult) {
          is MessengerSendProgress.Completed -> {
            println("🔐 [TrustManager] ✅ SUCCESS: Pairing rejection sent to $deviceId")
          }

          is MessengerSendProgress.Error -> {
            println("🔐 [TrustManager] ❌ FAILED: Could not send pairing rejection: ${sendResult.message}")
          }

          else -> {
            println("🔐 [TrustManager] ❌ UNEXPECTED: Pairing rejection send result: $sendResult")
          }
        }
      } catch (e: Exception) {
        println("🔐 [TrustManager] ❌ EXCEPTION: Failed to send pairing rejection: ${e.message}")
      }
    }
  }

  /**
   * Handle pairing response from target device.
   */
  suspend fun handlePairingResponse(response: TrustPairingResponse) = withContext(Dispatchers.Default) {
    val session = pairingSessions[response.deviceId] ?: return@withContext

    try {
      if (!response.accepted) {
        // TODO: Show rejection message to user
        return@withContext
      }

      // Compute shared secret (for future use)
      val sharedSecret = crypto.computeECDHSecret(
        privateKey = session.ecdhKeyPair.privateKey,
        peerPublicKeyBytes = response.ecdhPublicKey
      )

      // Store peer's keys
      storage.storeTrustedDevice(response.deviceId, response.ecdhPublicKey)  // ECDH key
      storage.storeECDSAKey(response.deviceId, response.ecdsaPublicKey)  // ECDSA key for verification

      // TODO: Show success message to user

    } catch (e: Exception) {
      // TODO: Show error message to user
    } finally {
      // Clean up session
      pairingSessions.remove(response.deviceId)
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
   * @param message Message bytes to sign
   * @return Signed TrustedMessage
   */
  suspend fun signMessage(message: ByteArray): TrustedMessage? {
    val signingKeys = deviceECDSAKeys ?: return null

    return try {
      val timestamp = clock.currentTimeMillis()
      val nonce = crypto.generateNonce()
      val currentDevice = currentDeviceProvider.get()

      // Create data to sign
      val dataToSign = crypto.combineForSigning(message, timestamp, nonce)

      // Sign data with ECDSA
      val signature = crypto.signWithECDSA(signingKeys.privateKey, dataToSign)

      TrustedMessage(
        payload = message,
        timestamp = timestamp,
        nonce = nonce,
        signature = signature,
        senderId = currentDevice.shortDeviceId
      )
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Verify a signed message from a trusted device.
   * @param trustedMessage Message to verify
   * @return true if signature is valid and from trusted device
   */
  suspend fun verifyMessage(trustedMessage: TrustedMessage): Boolean {
    try {
      // Get sender's ECDSA public key for verification
      val senderECDSAKey = storage.getECDSAKey(trustedMessage.senderId)
        ?: return false

      // Check timestamp to prevent replay attacks
      val currentTime = clock.currentTimeMillis()
      if (kotlin.math.abs(currentTime - trustedMessage.timestamp) > MAX_TIME_DIFF_SECONDS * 1000) {
        return false
      }

      // Recreate signed data
      val dataToVerify = crypto.combineForSigning(
        trustedMessage.payload,
        trustedMessage.timestamp,
        trustedMessage.nonce
      )

      // Verify ECDSA signature
      return crypto.verifyECDSA(senderECDSAKey, dataToVerify, trustedMessage.signature)

    } catch (e: Exception) {
      return false
    }
  }

  /**
   * Set callback for pairing approval dialogs.
   */
  fun setPairingApprovalCallback(callback: PairingApprovalCallback) {
    this.pairingApprovalCallback = callback
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