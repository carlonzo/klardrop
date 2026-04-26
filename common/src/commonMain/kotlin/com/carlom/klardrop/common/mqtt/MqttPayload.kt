package com.carlom.klardrop.common.mqtt

import kotlinx.serialization.Serializable

/**
 * The set of message bodies carried inside `SignedEnvelope.payload`.
 *
 * Sealed so the receiver can pattern-match all possibilities without a
 * wildcard branch — adding a new variant forces all handlers to update.
 *
 * Serialized via kotlinx-serialization-protobuf to match the rest of the
 * Klardrop wire format. Sealed-class polymorphism is encoded with a `type`
 * discriminator field by kotlinx.serialization.
 */
@Serializable
sealed class MqttPayload {

    /**
     * Sender announces a transfer; receiver decides accept/reject.
     * Note: with auto-accept enabled (sender already trusted), receivers
     * skip the prompt and respond ACCEPT immediately.
     */
    @Serializable
    data class TransferRequest(
        val transferId: String,
        val files: List<TransferFileMetadata>,
        val totalBytes: Long,
        val chunkSizeBytes: Int = DEFAULT_CHUNK_SIZE
    ) : MqttPayload()

    @Serializable
    data class TransferResponse(
        val transferId: String,
        val accepted: Boolean,
        val reason: String = ""
    ) : MqttPayload()

    @Serializable
    data class FileChunk(
        val transferId: String,
        val fileId: Long,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray,
        /** SHA-256 of `data` — receiver verifies before writing to disk. */
        val checksumSha256: ByteArray
    ) : MqttPayload() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileChunk) return false
            return transferId == other.transferId && fileId == other.fileId &&
                chunkIndex == other.chunkIndex && totalChunks == other.totalChunks &&
                data.contentEquals(other.data) && checksumSha256.contentEquals(other.checksumSha256)
        }

        override fun hashCode(): Int {
            var result = transferId.hashCode()
            result = 31 * result + fileId.hashCode()
            result = 31 * result + chunkIndex
            result = 31 * result + totalChunks
            result = 31 * result + data.contentHashCode()
            result = 31 * result + checksumSha256.contentHashCode()
            return result
        }
    }

    @Serializable
    data class TransferProgress(
        val transferId: String,
        val fileId: Long,
        val bytesTransferred: Long,
        val totalBytes: Long
    ) : MqttPayload()

    @Serializable
    data class TransferControl(
        val transferId: String,
        val command: ControlCommand,
        val reason: String = ""
    ) : MqttPayload()

    @Serializable
    data class TransferComplete(
        val transferId: String,
        val success: Boolean,
        val errorMessage: String = ""
    ) : MqttPayload()

    /**
     * Heartbeat / online indicator published by each device on
     * `klardrop/v1/users/{userId}/presence/{deviceId}` with retain=true.
     */
    @Serializable
    data class Presence(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val online: Boolean,
        val lastSeenMs: Long
    ) : MqttPayload()

    /**
     * Trust-set update broadcast by the device-registry on
     * `klardrop/v1/users/{userId}/trust/events` whenever a device is enrolled
     * or revoked. Receiving devices update their local TrustedDeviceCache.
     */
    @Serializable
    data class TrustEvent(
        val event: TrustEventType,
        val deviceId: String,
        val deviceName: String,
        val publicKey: ByteArray,
        val signatureAlgorithm: SignatureAlgorithm = SignatureAlgorithm.ED25519,
        val occurredAtMs: Long
    ) : MqttPayload() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TrustEvent) return false
            return event == other.event && deviceId == other.deviceId &&
                deviceName == other.deviceName && publicKey.contentEquals(other.publicKey) &&
                signatureAlgorithm == other.signatureAlgorithm &&
                occurredAtMs == other.occurredAtMs
        }

        override fun hashCode(): Int {
            var result = event.hashCode()
            result = 31 * result + deviceId.hashCode()
            result = 31 * result + deviceName.hashCode()
            result = 31 * result + publicKey.contentHashCode()
            result = 31 * result + signatureAlgorithm.hashCode()
            result = 31 * result + occurredAtMs.hashCode()
            return result
        }
    }

    companion object {
        const val DEFAULT_CHUNK_SIZE: Int = 64 * 1024
    }
}

@Serializable
enum class TrustEventType { ENROLLED, REVOKED }

@Serializable
enum class ControlCommand { PAUSE, RESUME, CANCEL, RETRY }

@Serializable
data class TransferFileMetadata(
    val fileId: Long,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String = ""
)
