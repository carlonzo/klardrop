package com.carlom.klardrop.common.communication

import com.carlom.klardrop.common.communication.message.FileChunkMessage
import com.carlom.klardrop.common.trust.TrustCrypto
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.operations.IvAuthenticatedCipher

/**
 * AEAD for file-chunk bodies, deliberately bypassing the UKEY2 D2D frame that carries every
 * other message.
 *
 * WHY THIS EXISTS: `D2DConnectionContext.encodeMessageToPeer` is a fine envelope for control
 * messages and a terrible one for bulk data. Measured on a 256 KB chunk (see
 * `ChunkPipelineBenchmark`), it costs 2.7 ms and allocates **13.8x the payload** — it wraps the
 * body in a SecureMessage protobuf, AES-CBC encrypts it, HMACs it, then wraps that in another
 * protobuf, copying the whole payload at each step. On a phone pushing 24 chunks/s that was
 * ~210 MB of garbage every 2 seconds, which pinned one core in ART's mark-compact collector and
 * capped transfers at ~4.5 MB/s over a link that measures 38 MB/s with `nc`.
 *
 * This path instead does one AES-GCM pass straight over the chunk body: one allocation for the
 * ciphertext, none for framing (the 17-byte header is written as its own tiny array), and the
 * metadata rides along as associated data rather than being copied into an envelope.
 *
 * SECURITY: the keys come from the live UKEY2 session's own `encodeKey`/`decodeKey` via HKDF, so
 * this inherits the session's forward secrecy and its authentication — it is not a fallback to
 * anything weaker, just a leaner envelope over the same secrets. Directions are keyed separately
 * (our send key is the peer's receive key), and each frame carries a monotonic counter that
 * becomes its GCM nonce, so a nonce is never reused under a key. Metadata (counter, transfer id,
 * sequence, last-chunk flag) is authenticated as AAD: tampering with any of it fails the tag.
 *
 * ponytail: AES-GCM, chosen on the numbers. Measured per 256 KB chunk: Pixel 9 Pro XL 1869 MB/s
 * (vs 876 for AES-CTR+HMAC and 574 for the AES-CBC+HMAC that UKEY2 uses), so it is the fastest
 * option on the side that was actually the bottleneck. The ceiling is the DESKTOP: this JDK 26 /
 * SunJCE does GCM at only 65 MB/s — AES is fine there (CTR 245, CBC 325 MB/s), it is GHASH that
 * misses its intrinsic, on a CPU where OpenSSL does 4.3 GB/s. 65 MB/s still clears the ~38 MB/s
 * this Wi-Fi link measures, so it does not bind today. If a faster link (or a desktop-side
 * profile) ever shows it binding, switch this to AES-CTR + HMAC-SHA256, which needs no protocol
 * change — only the two cipher constructions below and a version bump on the frame flag.
 *
 * THREADING: [seal] advances [sendCounter] and must be called under the connection's write mutex,
 * exactly like [FrameCipher.encode]. [open] is only ever called from the single read loop.
 */
class BulkCipher internal constructor(
  private val sendKey: IvAuthenticatedCipher,
  private val receiveKey: IvAuthenticatedCipher,
) {

  private var sendCounter = 0L

  /**
   * Encrypt one chunk body and return the frame's `[header][ciphertext]` halves separately so the
   * caller can write them without splicing them into a third array.
   *
   * [body] is used from index 0 up to [bodyLength]; pass the sender's reused read buffer directly
   * for a full chunk (no copy) and only copy for a short final one.
   */
  @OptIn(DelicateCryptographyApi::class)
  suspend fun seal(
    fileMessageId: Int,
    seq: Int,
    isLast: Boolean,
    body: ByteArray,
    bodyLength: Int = body.size,
  ): SealedChunk {
    val counter = sendCounter++
    val header = ByteArray(HEADER_SIZE)
    header.putLong(0, counter)
    header.putInt(8, fileMessageId)
    header.putInt(12, seq)
    header[16] = if (isLast) 1 else 0

    // GCM wants the exact plaintext range and the API takes a whole array, so a short final chunk
    // is the one case that has to be copied. Full chunks encrypt the caller's buffer in place.
    val plaintext = if (bodyLength == body.size) body else body.copyOf(bodyLength)
    val ciphertext = sendKey.encryptWithIv(nonceOf(counter), plaintext, header)
    return SealedChunk(header, ciphertext)
  }

  /**
   * Decrypt one bulk frame back into the [FileChunkMessage] the router already knows how to
   * handle, so nothing downstream of the read loop has to care which envelope carried it.
   *
   * Throws if the frame is truncated or fails authentication — the read loop treats that as fatal
   * for the connection, which is correct: a bad tag means the stream is corrupt or forged.
   */
  @OptIn(DelicateCryptographyApi::class)
  suspend fun open(frame: ByteArray): FileChunkMessage {
    require(frame.size > HEADER_SIZE) { "Bulk frame too short: ${frame.size} bytes" }
    val counter = frame.getLong(0)
    val header = frame.copyOf(HEADER_SIZE)
    val body = receiveKey.decryptWithIv(
      nonceOf(counter),
      frame.copyOfRange(HEADER_SIZE, frame.size),
      header,
    )
    return FileChunkMessage(
      fileMessageId = frame.getInt(8),
      seq = frame.getInt(12),
      data = body,
      isLast = frame[16].toInt() == 1,
    )
  }

  /** 12-byte GCM nonce: 4 zero bytes then the frame counter. Unique per (key, frame) by construction. */
  private fun nonceOf(counter: Long): ByteArray = ByteArray(NONCE_SIZE).also { it.putLong(4, counter) }

  /** The two halves of a bulk frame, kept apart so neither has to be copied into the other. */
  class SealedChunk(val header: ByteArray, val ciphertext: ByteArray) {
    val wireSize: Int get() = header.size + ciphertext.size
  }

  companion object {
    /** counter(8) + fileMessageId(4) + seq(4) + isLast(1) — also the AAD for every frame. */
    internal const val HEADER_SIZE = 17
    private const val NONCE_SIZE = 12
    private val BULK_INFO = "klardrop-bulk-chunk-v1".encodeToByteArray()

    /**
     * Derive the two directional keys from the live UKEY2 session keys. Our send key is derived
     * from `encodeKey` and the peer derives its receive key from its `decodeKey`, which is the
     * same secret — so both sides land on the same key without any extra handshake.
     */
    internal suspend fun fromSessionKeys(
      encodeKey: ByteArray,
      decodeKey: ByteArray,
      crypto: TrustCrypto = TrustCrypto(),
    ): BulkCipher {
      val gcm = CryptographyProvider.Default.get(AES.GCM)
      suspend fun cipherFor(sessionKey: ByteArray): IvAuthenticatedCipher {
        val derived = crypto.hkdfSha256(sharedSecret = sessionKey, info = BULK_INFO, outputSize = 32)
        return gcm.keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, derived).cipher() // default 128-bit tag
      }
      return BulkCipher(sendKey = cipherFor(encodeKey), receiveKey = cipherFor(decodeKey))
    }
  }
}

private fun ByteArray.putInt(offset: Int, value: Int) {
  this[offset] = (value ushr 24).toByte()
  this[offset + 1] = (value ushr 16).toByte()
  this[offset + 2] = (value ushr 8).toByte()
  this[offset + 3] = value.toByte()
}

private fun ByteArray.putLong(offset: Int, value: Long) {
  putInt(offset, (value ushr 32).toInt())
  putInt(offset + 4, value.toInt())
}

private fun ByteArray.getInt(offset: Int): Int =
  ((this[offset].toInt() and 0xFF) shl 24) or
    ((this[offset + 1].toInt() and 0xFF) shl 16) or
    ((this[offset + 2].toInt() and 0xFF) shl 8) or
    (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.getLong(offset: Int): Long =
  ((getInt(offset).toLong() and 0xFFFFFFFFL) shl 32) or (getInt(offset + 4).toLong() and 0xFFFFFFFFL)
