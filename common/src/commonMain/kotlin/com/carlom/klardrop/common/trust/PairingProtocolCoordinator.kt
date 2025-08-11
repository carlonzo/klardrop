package com.carlom.klardrop.common.trust

import com.carlom.klardrop.common.communication.Messenger
import com.carlom.klardrop.common.communication.MessengerSendProgress
import com.carlom.klardrop.common.communication.message.TrustPairingRequest
import com.carlom.klardrop.common.communication.message.TrustPairingResponse
import com.carlom.klardrop.common.communication.message.toSimpleSendRequest
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

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
                }
            }
            .launchIn(scope)
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

