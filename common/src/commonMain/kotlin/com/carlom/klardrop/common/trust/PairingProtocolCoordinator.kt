package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.TrustRevocationMessage
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Coordinates pairing protocol communication between TrustManager and Messenger.
 * This class acts as an adapter layer, handling all messaging operations
 * while TrustManager remains focused on trust logic and cryptography.
 * 
 * Responsibilities:
 * - Send pairing requests and responses via Messenger
 * - Handle incoming pairing messages and delegate to TrustManager
 * - Manage communication errors and retries
 * - Isolate TrustManager from networking concerns
 */
class PairingProtocolCoordinator(
    private val trustManager: TrustManager,
    private val messenger: Messenger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Callback for when pairing is completed (for UI updates)
    var onPairingCompleted: ((deviceId: String, deviceName: String, success: Boolean) -> Unit)? = null

    /**
     * Emits whenever a verified peer revocation has been applied locally — typically because
     * the peer tapped "Forget device" on their end (or because we contacted a peer who no
     * longer trusts us and they replied with a revocation). Consumers (DiscoveryController)
     * surface this to the user as a banner with Dismiss / Pair actions.
     */
    private val _peerRevokedTrust = MutableSharedFlow<PeerRevokedTrust>(extraBufferCapacity = 8)
    val peerRevokedTrust: SharedFlow<PeerRevokedTrust> = _peerRevokedTrust.asSharedFlow()

    data class PeerRevokedTrust(val deviceId: String, val reason: String?)

    init {
        // Listen to pairing events from TrustManager
        trustManager.pairingEvents
            .onEach { event ->
                when (event) {
                    is PairingEvent.SendPairingRequest -> {
                        sendPairingRequest(event.targetDeviceId, event.request)
                    }
                    is PairingEvent.SendPairingResponse -> {
                        sendPairingResponse(event.targetDeviceId, event.response)
                    }
                    is PairingEvent.PairingRequestReceived -> {
                        event.decision?.showApprovalDialog(
                            onAccept = {
                                println("🔐 [PairingProtocolCoordinator] User accepted pairing with ${event.request.deviceName}")
                                acceptPairing(event.request, event.senderAddress)
                            },
                            onReject = {
                                println("🔐 [PairingProtocolCoordinator] User rejected pairing with ${event.request.deviceName}")
                                rejectPairing(event.request.deviceId)
                            }
                        )
                    }
                    is PairingEvent.PairingCompleted -> {
                        println("🔐 [PairingProtocolCoordinator] Pairing completed for ${event.deviceName} (${event.deviceId}), success: ${event.success}")
                        onPairingCompleted?.invoke(event.deviceId, event.deviceName, event.success)
                    }

                    is PairingEvent.PeerRevokedTrust -> {
                        log("PairingProtocolCoordinator", "Peer ${event.deviceId} revoked trust (reason=${event.reason})")
                        _peerRevokedTrust.tryEmit(PeerRevokedTrust(event.deviceId, event.reason))
                    }
                }
            }
            .launchIn(scope)
    }

    /**
     * Forget [targetDeviceId] locally and best-effort notify the peer.
     *
     * Order matters: we send the revocation BEFORE removing local trust. The send path
     * needs the peer's identity in storage so the Messenger can pick a transport, and the
     * peer needs us to be able to sign the revocation with our identity (independent of
     * theirs — that part still works post-removal, but their delivery may not, so we err
     * on the side of "notify, then forget").
     *
     * Send failures (peer offline, transport down) are tolerated — local removal happens
     * either way. If/when the peer next contacts us with a TrustedMessage we can no longer
     * verify, the reactive mismatch path in [MessagesRouter] will send them a fresh
     * revocation and heal the asymmetry.
     */
    suspend fun unpair(targetDeviceId: String, reason: String? = null) {
        log("PairingProtocolCoordinator", "unpair($targetDeviceId)")
        val revocation = trustManager.createRevocationMessage(targetDeviceId, reason)
        if (revocation != null) {
            // 3-second cap so a misbehaving peer can't hang the user's unpair tap. The
            // revocation is best-effort by design; missed deliveries get healed reactively.
            withTimeoutOrNull(3.seconds) {
                runCatching {
                    messenger.send(targetDeviceId, revocation.toSimpleSendRequest())
                        .first { it.isCompleted() }
                }.onFailure {
                    log("PairingProtocolCoordinator", "Revocation send failed for $targetDeviceId: ${it.message}")
                }
            } ?: log("PairingProtocolCoordinator", "Revocation send timed out for $targetDeviceId; proceeding with local removal")
        } else {
            log("PairingProtocolCoordinator", "createRevocationMessage returned null; skipping notify and removing locally")
        }
        trustManager.removeTrust(targetDeviceId)
    }

    /**
     * Initiate pairing with a target device.
     * Delegates request creation to TrustManager and handles sending.
     */
    suspend fun initiatePairing(targetDeviceId: String): Result<Unit> {
        println("🔐 [PairingProtocolCoordinator] initiatePairing() called for deviceId: $targetDeviceId")
        
        return try {
            // Get the pairing request from TrustManager (pure domain logic)
            val requestResult = trustManager.createPairingRequest(targetDeviceId)
            
            if (requestResult.isFailure) {
                println("🔐 [PairingProtocolCoordinator] Failed to create pairing request: ${requestResult.exceptionOrNull()?.message}")
                return requestResult.map { }
            }
            
            val request = requestResult.getOrThrow()
            println("🔐 [PairingProtocolCoordinator] Created TrustPairingRequest: ${request.deviceId} -> $targetDeviceId")
            
            // Send the request via Messenger (infrastructure concern)
            sendPairingRequest(targetDeviceId, request)
        } catch (e: Exception) {
            println("🔐 [PairingProtocolCoordinator] Exception during initiatePairing: ${e.message}")
            Result.failure(e)
        }
    }


    /**
     * Accept a pairing request and send the response.
     */
    private fun acceptPairing(request: TrustPairingRequest, senderAddress: String) {
        scope.launch {
            try {
                // Get acceptance response from TrustManager
                val responseResult = trustManager.createPairingAcceptance(request)
                
                if (responseResult.isFailure) {
                    println("🔐 [PairingProtocolCoordinator] Failed to create acceptance: ${responseResult.exceptionOrNull()?.message}")
                    // Fall back to rejection
                    rejectPairing(request.deviceId)
                    return@launch
                }
                
                val response = responseResult.getOrThrow()
                println("🔐 [PairingProtocolCoordinator] Sending acceptance to ${request.deviceId}")
                
                // Send the response
                sendPairingResponse(request.deviceId, response)
                
            } catch (e: Exception) {
                println("🔐 [PairingProtocolCoordinator] Exception during acceptPairing: ${e.message}")
                // Fall back to rejection on error
                rejectPairing(request.deviceId)
            }
        }
    }

    /**
     * Reject a pairing request and send the response.
     */
    private fun rejectPairing(deviceId: String) {
        scope.launch {
            try {
                // Get rejection response from TrustManager
                val response = trustManager.createPairingRejection(deviceId)
                println("🔐 [PairingProtocolCoordinator] Sending rejection to $deviceId")
                
                // Send the response
                sendPairingResponse(deviceId, response)
                
            } catch (e: Exception) {
                println("🔐 [PairingProtocolCoordinator] Exception during rejectPairing: ${e.message}")
            }
        }
    }

    /**
     * Send a pairing request via Messenger.
     */
    private suspend fun sendPairingRequest(
        targetDeviceId: String,
        request: TrustPairingRequest
    ): Result<Unit> {
        return try {
            val sendResult = messenger.send(targetDeviceId, request.toSimpleSendRequest())
                .first { it.isCompleted() }

            when (sendResult) {
                is MessengerSendProgress.Completed -> {
                    println("🔐 [PairingProtocolCoordinator] ✅ Pairing request sent to $targetDeviceId")
                    // Notify TrustManager that request was sent successfully
                    trustManager.onPairingRequestSent(targetDeviceId)
                    Result.success(Unit)
                }
                
                is MessengerSendProgress.Error -> {
                    println("🔐 [PairingProtocolCoordinator] ❌ Failed to send pairing request: ${sendResult.message}")
                    // Notify TrustManager of send failure for cleanup
                    trustManager.onPairingRequestFailed(targetDeviceId)
                    Result.failure(Exception("Failed to send pairing request: ${sendResult.message}"))
                }
                
                else -> {
                    println("🔐 [PairingProtocolCoordinator] ❌ Unexpected send result: $sendResult")
                    trustManager.onPairingRequestFailed(targetDeviceId)
                    Result.failure(Exception("Unexpected send result: $sendResult"))
                }
            }
        } catch (e: Exception) {
            println("🔐 [PairingProtocolCoordinator] ❌ Exception sending pairing request: ${e.message}")
            trustManager.onPairingRequestFailed(targetDeviceId)
            Result.failure(e)
        }
    }

    /**
     * Send a pairing response via Messenger.
     */
    private suspend fun sendPairingResponse(
        targetDeviceId: String,
        response: TrustPairingResponse
    ) {
        try {
            val sendResult = messenger.send(targetDeviceId, response.toSimpleSendRequest())
                .first { it.isCompleted() }

            when (sendResult) {
                is MessengerSendProgress.Completed -> {
                    println("🔐 [PairingProtocolCoordinator] ✅ Pairing response sent to $targetDeviceId")
                }
                
                is MessengerSendProgress.Error -> {
                    println("🔐 [PairingProtocolCoordinator] ❌ Failed to send pairing response: ${sendResult.message}")
                }
                
                else -> {
                    println("🔐 [PairingProtocolCoordinator] ❌ Unexpected send result: $sendResult")
                }
            }
        } catch (e: Exception) {
            println("🔐 [PairingProtocolCoordinator] ❌ Exception sending pairing response: ${e.message}")
        }
    }
}

