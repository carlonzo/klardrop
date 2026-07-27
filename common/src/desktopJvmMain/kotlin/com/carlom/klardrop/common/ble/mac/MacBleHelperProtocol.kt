package com.carlom.klardrop.common.ble.mac

import com.carlom.klardrop.common.ble.BleAdvertisementCodec
import com.carlom.klardrop.common.ble.BlePeerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Newline-delimited JSON protocol shared with the Swift `klardrop-ble-helper`
 * binary in `desktop/native/macos/`. The Kotlin side speaks this over the helper's
 * stdin (commands) and stdout (responses + events).
 *
 * Wire shape:
 *  - Command:  `{"id": "<uuid>", "cmd": "<name>", ...}`
 *  - Response: `{"id": "<uuid>", "ok": true, ...}` or `{"id": ..., "ok": false, "error": ..., "message": ...}`
 *  - Event:    `{"event": "<name>", ...}` (no `id`)
 *
 * Binary BLE chunks are base64 strings under the `data` field.
 */
internal object HelperCommands {
  const val INIT = "init"
  const val SCAN_START = "scan_start"
  const val SCAN_STOP = "scan_stop"
  const val ADVERTISE_START = "advertise_start"
  const val ADVERTISE_STOP = "advertise_stop"
  const val CONNECT = "connect"
  const val SEND_CHUNK = "send_chunk"
  const val CLOSE_SESSION = "close_session"
  const val SHUTDOWN = "shutdown"
}

internal object HelperEvents {
  const val STATE = "state"

  /**
   * `{"event":"peer_found","peerId":...,"rssi":...,"serviceData":<base64>?,"localName":?}`
   *
   * Carries the raw advertisement fields, not a decoded identity: what counts as a
   * Klardrop peer is decided by `BleAdvertisementCodec` in commonMain so the helper can't
   * drift from the Android and iOS transports. Helper binaries predating that change send
   * a decoded `shortDeviceId` instead — see the PEER_FOUND branch in MacBleHelperProcess.
   */
  const val PEER_FOUND = "peer_found"
  const val PEER_LOST = "peer_lost"
  const val SESSION_OPENED = "session_opened"
  const val CHUNK = "chunk"
  const val SESSION_CLOSED = "session_closed"
  const val LOG = "log"
}

internal object HelperProtocol {

  val json: Json = Json { ignoreUnknownKeys = true }

  /** Build a single-line JSON command. The trailing newline is the caller's job. */
  fun encodeRequest(id: String, cmd: String, fields: Map<String, JsonElement> = emptyMap()): String {
    val obj = buildJsonObject {
      put("id", id)
      put("cmd", cmd)
      for ((k, v) in fields) put(k, v)
    }
    return obj.toString()
  }

  /** Parse one helper-stdout line. Returns null on malformed input. */
  fun parseLine(line: String): HelperLine? {
    val element = runCatching { json.parseToJsonElement(line) }.getOrNull() ?: return null
    val obj = element as? JsonObject ?: return null

    val event = obj["event"]?.jsonPrimitive?.contentOrNull
    if (event != null) return HelperLine.Event(event, obj)

    val id = obj["id"]?.jsonPrimitive?.contentOrNull
    val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull
    if (id != null && ok == true) return HelperLine.Ok(id, obj)
    if (id != null && ok == false) {
      return HelperLine.Error(
        id = id,
        code = obj["error"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
      )
    }
    return null
  }
}

internal sealed interface HelperLine {
  data class Ok(val id: String, val obj: JsonObject) : HelperLine
  data class Error(val id: String, val code: String, val message: String) : HelperLine
  data class Event(val name: String, val obj: JsonObject) : HelperLine
}

/**
 * Turn a `peer_found` event into a [BlePeerEvent.Found], or null when the advertisement
 * carries no Klardrop identity.
 *
 * The identity rules themselves live in [BleAdvertisementCodec] in commonMain, shared
 * with the Android and iOS transports — this only lifts the fields off the wire.
 *
 * The second decode handles an older helper binary. The helper is checked into the repo
 * as a prebuilt artifact and can only be regenerated on a Mac
 * (`scripts/build-mac-ble-helper.sh`), so until it is rebuilt the running binary decodes
 * the advertisement itself and sends `shortDeviceId` — with `localName` set to the peer's
 * *GAP device name* rather than the AD local name, which is why it can't just be passed
 * as the name candidate. Both paths run through the same validator, so neither can admit
 * an id the other would reject.
 */
internal fun decodePeerFound(obj: JsonObject): BlePeerEvent.Found? {
  val address = obj.string("peerId") ?: return null
  val serviceData = obj.string("serviceData")
    ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
  val advertisement = BleAdvertisementCodec.decode(serviceData, obj.string("localName"))
    ?: BleAdvertisementCodec.decode(null, obj.string("shortDeviceId"))
    ?: return null
  return BlePeerEvent.Found(
    address = address,
    shortDeviceId = advertisement.shortDeviceId,
    localName = advertisement.friendlyName,
    rssi = obj.int("rssi") ?: 0,
  )
}

// Convenience accessors for typed event/response fields.
internal fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
internal fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
internal fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

internal fun stringField(value: String): JsonElement = JsonPrimitive(value)
internal fun intField(value: Int): JsonElement = JsonPrimitive(value)
