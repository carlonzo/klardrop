package com.carlom.klardrop.common.ble

/**
 * A connected BLE GATT session between the local peer and a remote peer.
 *
 * This is the transport primitive the Klardrop communication layer runs over when the TCP
 * path isn't available. The API is deliberately chunk-oriented: BLE has no notion of a
 * stream, so callers push/pull discrete MTU-sized payloads. [BleChannelBridge] wraps a
 * session to present the Ktor `ByteReadChannel` / `ByteWriteChannel` pair that the
 * existing Client/Server code expects.
 *
 * Lifecycle:
 *  - The session is live from construction until [close] is called or the remote closes
 *    the GATT connection. After that, [isOpen] is false and [receiveChunk] returns null.
 *  - [close] is idempotent.
 *
 * Implementations:
 *  - `Android`: wraps `BluetoothGatt` (central role) or `BluetoothGattServer` (peripheral).
 *  - `Apple`: wraps `CBPeripheral` / `CBCentralManager`.
 *  - `JVM desktop`: wraps BlueZ D-Bus characteristics (Linux) or a Swift helper process (macOS).
 *  - `Tests`: a fake backed by `kotlinx.coroutines.channels.Channel<ByteArray>`.
 */
interface BleSession {

  /** The remote peer's short device id, negotiated during the app-level handshake. */
  val deviceId: String

  /** Whether the underlying GATT connection is currently open. */
  val isOpen: Boolean

  /**
   * Maximum payload bytes allowed per [sendChunk] call (MTU minus the ATT header).
   * Stable for the lifetime of the session — MTU negotiation happens once on connect.
   */
  val mtu: Int

  /**
   * Send one chunk (≤ [mtu] bytes) to the remote peer over the TX GATT characteristic.
   * Suspends until the write completes (write-with-response) so that chunks delivered in
   * order from the caller also arrive in order on the remote peer.
   *
   * @throws IllegalArgumentException if [chunk] is larger than [mtu].
   * @throws IllegalStateException if the session is closed.
   */
  suspend fun sendChunk(chunk: ByteArray)

  /**
   * Suspend until the next chunk from the remote peer arrives (a GATT characteristic
   * notification on Android / update callback on Apple). Returns `null` when the session
   * closes, at which point no further chunks will arrive.
   */
  suspend fun receiveChunk(): ByteArray?

  /** Close the GATT connection and free native resources. Idempotent. */
  fun close()
}
