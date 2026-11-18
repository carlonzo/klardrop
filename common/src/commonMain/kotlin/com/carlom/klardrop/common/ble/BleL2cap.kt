package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

private const val TAG = "BleL2cap"

/** Encoding of the [BleConstants.PSM_CHARACTERISTIC_UUID] value: unsigned 16-bit little-endian. */
internal object BlePsm {
  fun encode(psm: Int): ByteArray {
    require(psm in 1..0xFFFF) { "PSM out of range: $psm" }
    return byteArrayOf((psm and 0xFF).toByte(), (psm shr 8).toByte())
  }

  /** Returns null for null input, a size other than 2, or PSM 0. */
  fun decode(bytes: ByteArray?): Int? {
    if (bytes == null || bytes.size != 2) return null
    val psm = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
    return psm.takeIf { it != 0 }
  }
}

/**
 * Prefer an L2CAP CoC session, falling back to GATT. [readPsm] then [openL2cap] share ONE
 * [timeoutMs] budget. [readPsm] returning null (no PSM characteristic), a timeout, or any
 * non-cancellation failure of either step yields [openGatt]. Caller cancellation is rethrown
 * and never falls back. Errors from [openGatt] propagate.
 *
 * Contract: [openL2cap] MUST tear down its half-open attempt when cancelled (platforms close
 * the socket/channel in invokeOnCancellation).
 */
internal suspend fun connectPreferringL2cap(
  peerLabel: String,
  readPsm: suspend () -> Int?,
  openL2cap: suspend (psm: Int) -> BleSession,
  openGatt: suspend () -> BleSession,
  timeoutMs: Long = BleConstants.L2CAP_OPEN_TIMEOUT_MS,
): BleSession {
  var reason: String
  try {
    val l2cap = withTimeout(timeoutMs) {
      val psm = readPsm()
      if (psm == null) null else psm to openL2cap(psm)
    }
    if (l2cap != null) {
      log(TAG, "BLE session to $peerLabel via L2CAP psm=${l2cap.first}")
      return l2cap.second
    }
    reason = "no L2CAP PSM"
  } catch (e: TimeoutCancellationException) {
    reason = "L2CAP timed out after ${timeoutMs}ms"
  } catch (e: Throwable) {
    // Caller cancelled (not our timeout): rethrow instead of falling back.
    currentCoroutineContext().ensureActive()
    reason = "L2CAP failed: ${e.message}"
  }
  log(TAG, "BLE session to $peerLabel via GATT ($reason)")
  return openGatt()
}
