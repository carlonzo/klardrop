package com.carlom.klardrop.common.qrshare

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.flow.Flow

expect class LanTlsListener() {
  /**
   * Self-signed ECDSA P-256.
   * SAN: IPAddress = [ipv4] (GeneralName [7]).
   * KeyUsage: digitalSignature.
   * EKU: id-kp-serverAuth (1.3.6.1.5.5.7.3.1) — iOS rejects without it.
   * Cert signature: DER SEQUENCE of two INTEGERs, not TrustCrypto raw r||s.
   *
   * Listen on 0.0.0.0:[port] (port 0 = OS-assigned). The [ipv4] argument is ONLY
   * for the certificate SAN / advertised host, not the bind address.
   */
  suspend fun bind(ipv4: String, port: Int = 0): Bound
  fun incoming(): Flow<TlsConnection>  // already decrypted HTTP/1.1 bytes + peer IPv4
  fun close()
}

data class Bound(val port: Int)

class TlsConnection(
  val peerIpv4: String,
  val input: ByteReadChannel,   // io.ktor.utils.io
  val output: ByteWriteChannel,
  val close: () -> Unit,
)
