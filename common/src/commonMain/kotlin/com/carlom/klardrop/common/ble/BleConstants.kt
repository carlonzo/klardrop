package com.carlom.klardrop.common.ble

/**
 * Service and characteristic UUIDs for the Klardrop BLE GATT service.
 *
 * A single primary service exposes two characteristics that together form a bidirectional
 * byte pipe between a central (client) and a peripheral (GATT server):
 *
 *  - [TX_CHARACTERISTIC_UUID]: client writes frames TO the server. Write-with-response,
 *    chunked to negotiated MTU.
 *  - [RX_CHARACTERISTIC_UUID]: server pushes frames to the client via notifications.
 *
 * An optional third, read-only characteristic [PSM_CHARACTERISTIC_UUID] advertises an
 * insecure L2CAP CoC PSM (unsigned 16-bit little-endian, exactly 2 bytes). Centrals that find
 * it run the whole session over L2CAP instead; old peers ignore it.
 *
 * The wire format on both characteristics is the same as the TCP transport:
 * `[4-byte big-endian length][1-byte type][protobuf payload]`, reassembled across
 * MTU-sized chunks.
 *
 * The advertisement packet includes [SERVICE_UUID] in the service-uuids AD field and a
 * short local name derived from the current device's `shortDeviceId` (≤8 chars) so peers
 * can filter their scans.
 */
internal object BleConstants {

  /** Primary service UUID. Hand-picked random v4 UUID — must stay stable across releases. */
  const val SERVICE_UUID = "a5b7c3e1-7f5a-4b62-9a3c-1d8e2f4b6c8a"

  /** Client-to-server data channel (write, no notify). */
  const val TX_CHARACTERISTIC_UUID = "a5b7c3e2-7f5a-4b62-9a3c-1d8e2f4b6c8a"

  /** Server-to-client data channel (notify). */
  const val RX_CHARACTERISTIC_UUID = "a5b7c3e3-7f5a-4b62-9a3c-1d8e2f4b6c8a"

  /** Read-only: PSM of the peripheral's L2CAP CoC as unsigned 16-bit little-endian (2 bytes). */
  const val PSM_CHARACTERISTIC_UUID = "a5b7c3e4-7f5a-4b62-9a3c-1d8e2f4b6c8a"

  /** Budget for the PSM read plus the L2CAP channel open, together. */
  const val L2CAP_OPEN_TIMEOUT_MS = 5_000L

  /** `mtu` reported by an L2CAP-backed [BleSession]: max bytes per sendChunk / read buffer size. */
  const val L2CAP_CHUNK_SIZE = 8192

  /** Standard Client Characteristic Configuration Descriptor for enabling notifications. */
  const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

  /** Default BLE MTU before negotiation (spec minimum is 23, 3 bytes ATT header → 20 payload). */
  const val DEFAULT_MTU = 23

  /** Target MTU we request post-connect; common practical ceiling across stacks. */
  const val REQUESTED_MTU = 247

  /** Bytes reserved by the ATT header inside an MTU. */
  const val ATT_HEADER_SIZE = 3
}
