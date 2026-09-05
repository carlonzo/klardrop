package com.carlom.klardrop.debug

import com.carlom.klardrop.DiscoveryController
import com.carlom.klardrop.TrustStatus
import com.carlom.klardrop.common.Klardrop
import com.carlom.klardrop.common.communication.Reachability
import com.carlom.klardrop.common.receiver.ReceiveMessageStatus
import com.carlom.klardrop.common.utils.LogBuffer
import com.carlom.klardrop.common.utils.log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Loopback HTTP control plane. Maps each endpoint onto the same DiscoveryController
 * methods the Compose buttons call, so an agent can pair / send / accept without
 * tapping the UI.
 *
 * Started only when [com.carlom.klardrop.common.ApplicationInfo.isDebug] is true and
 * [com.carlom.klardrop.common.ApplicationInfo.controlPort] is set. Binds 127.0.0.1.
 */
object DebugControl {
  private val json = Json { ignoreUnknownKeys = true }

  @Volatile
  private var controller: DiscoveryController? = null

  @Volatile
  private var klardrop: Klardrop? = null

  @Volatile
  var windowVisibilityProvider: (() -> Boolean)? = null

  @Volatile
  var windowVisibilitySetter: ((Boolean) -> Unit)? = null

  private var server: LoopbackHttpServer? = null

  suspend fun start(app: Klardrop) {
    klardrop = app
    val info = app.commonComponent.applicationInfo()
    if (!info.isDebug) return
    val port = info.controlPort ?: return
    if (port <= 0) return
    if (server != null) return
    val http = LoopbackHttpServer(
      host = "127.0.0.1",
      port = port,
      dispatcher = app.commonComponent.coroutines().ioDispatcher,
      handle = ::dispatch,
    )
    server = http
    withContext(app.commonComponent.coroutines().ioDispatcher) {
      http.start()
    }
  }

  suspend fun bind(discoveryController: DiscoveryController, app: Klardrop) {
    controller = discoveryController
    start(app)
  }

  fun stop() {
    server?.stop()
    server = null
  }

  private suspend fun dispatch(request: HttpRequest): HttpResponse {
    val path = request.path.trimEnd('/').ifEmpty { "/" }
    val body = parseBody(request.body)
    return when {
      request.method == "GET" && path == "/health" ->
        HttpResponse(200, jsonOk(""" "port":${server?.boundPort ?: 0} """))

      request.method == "GET" && path == "/window" -> {
        val visible = windowVisibilityProvider?.invoke() ?: false
        val payload = buildJsonObject {
          put("ok", true)
          put("visible", visible)
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "POST" && path == "/window" -> {
        val visible = body.boolean("visible") ?: error("missing visible")
        log("DebugControl", "action window visible=$visible")
        windowVisibilitySetter?.invoke(visible)
        val payload = buildJsonObject {
          put("ok", true)
          put("visible", visible)
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "GET" && path == "/state" ->
        HttpResponse(200, snapshotState())

      request.method == "GET" && path == "/logs" -> {
        val limit = queryParam(request.query, "limit")?.toIntOrNull() ?: 400
        val lines = LogBuffer.snapshot(limit)
        val payload = buildJsonObject {
          put("ok", true)
          putJsonArray("lines") { lines.forEach { add(JsonPrimitive(it)) } }
        }.toString()
        HttpResponse(200, payload)
      }

      request.method == "POST" && path == "/pair" ->
        action("pair") { it.debugPair(requireDeviceId(body)) }

      request.method == "POST" && path == "/unpair" -> {
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        log("DebugControl", "action unpair")
        ctrl.debugUnpairAndWait(deviceId)
        HttpResponse(200, jsonOk(""" "action":"unpair" """))
      }

      request.method == "POST" && path == "/accept-pair" ->
        action("accept-pair") { it.debugAcceptPairing(requireDeviceId(body)) }

      request.method == "POST" && path == "/reject-pair" ->
        action("reject-pair") { it.debugRejectPairing(requireDeviceId(body)) }

      request.method == "POST" && path == "/send-text" -> {
        val text = body.string("text") ?: error("missing text")
        val ctrl = controller ?: error("DiscoveryController not bound")
        val deviceId = requireDeviceId(body)
        log("DebugControl", "action send-text")
        val result = ctrl.debugSendTextAndWait(deviceId, text)
        HttpResponse(200, jsonOk(""" "action":"send-text","result":${jsonString(result)} """))
      }

      request.method == "POST" && path == "/send-file" -> {
        val filePath = body.string("path") ?: error("missing path")
        action("send-file") { it.debugSendFile(requireDeviceId(body), filePath) }
      }

      request.method == "POST" && path == "/accept-incoming" -> {
        val receiveId = body.int("receiveId")
        val deviceId = body.string("deviceId")
        action("accept-incoming") { ctrl ->
          when {
            receiveId != null -> ctrl.debugAcceptIncoming(receiveId)
            deviceId != null -> ctrl.debugAcceptIncomingFrom(deviceId)
            else -> error("missing receiveId or deviceId")
          }
        }
      }

      request.method == "POST" && path == "/reject-incoming" -> {
        val receiveId = body.int("receiveId") ?: error("missing receiveId")
        action("reject-incoming") { it.debugRejectIncoming(receiveId) }
      }

      request.method == "POST" && path == "/reset-identity" -> {
        val app = klardrop ?: error("not bound")
        val shortId = app.commonComponent.currentDeviceProvider().rotateDeviceId()
        app.commonComponent.trustManager().resetIdentity()
        app.commonComponent.incomingAuthorizer().clearFirstContact()
        log("DebugControl", "reset-identity -> $shortId")
        HttpResponse(200, jsonOk(""" "deviceId":${jsonString(shortId)} """))
      }

      request.method == "POST" && path == "/refresh-permissions" ->
        action("refresh-permissions") { it.refreshPermissions() }

      else -> HttpResponse(404, jsonError("unknown ${request.method} $path"))
    }
  }

  private fun action(name: String, block: (DiscoveryController) -> Unit): HttpResponse {
    val ctrl = controller ?: error("DiscoveryController not bound")
    log("DebugControl", "action $name")
    block(ctrl)
    return HttpResponse(200, jsonOk(""" "action":${jsonString(name)} """))
  }

  private fun parseBody(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return json.parseToJsonElement(trimmed) as? JsonObject
      ?: error("body must be a JSON object")
  }

  private fun requireDeviceId(body: JsonObject): String =
    body.string("deviceId") ?: error("missing deviceId")

  private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.int(key: String): Int? =
    this[key]?.jsonPrimitive?.intOrNull

  private fun JsonObject.boolean(key: String): Boolean? =
    this[key]?.jsonPrimitive?.booleanOrNull

  private suspend fun snapshotState(): String {
    val app = klardrop ?: error("not bound")
    val ctrl = controller ?: error("DiscoveryController not bound")
    val info = app.commonComponent.applicationInfo()
    val self = app.commonComponent.currentDeviceProvider().get()
    val state = ctrl.screenStateFlow.value
    val trusted = app.commonComponent.trustManager().getTrustedDevices()
    return buildJsonObject {
      put("ok", true)
      putJsonObject("self") {
        put("deviceId", self.shortDeviceId)
        put("deviceName", self.deviceName)
        put("osType", self.osType.name)
        put("deviceType", self.deviceType.name)
      }
      putJsonObject("protocols") {
        put("klardrop", info.enableKlardropServer)
        put("nearby", info.enableNearbyServer)
        put("ble", info.enableBle)
      }
      putJsonArray("devices") {
        state.devices.forEach { device ->
          add(
            buildJsonObject {
              put("deviceId", device.deviceId)
              put("deviceName", device.deviceName)
              put("deviceType", device.deviceType.name)
              put("trustStatus", device.trustStatus.label())
              put("reachability", device.reachability.label())
              put("hasUnread", device.hasUnreadMessages)
              putNullable("pairingError", device.pairingError)
              putJsonArray("connectionTypes") {
                device.connectionTypes.forEach { add(JsonPrimitive(it.name)) }
              }
            },
          )
        }
      }
      putJsonArray("trustedIds") {
        trusted.forEach { add(JsonPrimitive(it.deviceId)) }
      }
      val dialog = state.pairingDialogState
      if (dialog == null) {
        put("pairingDialog", JsonNull)
      } else {
        putJsonObject("pairingDialog") {
          put("deviceId", dialog.deviceId)
          put("deviceName", dialog.deviceName)
          put("isError", dialog.isError)
          putNullable("errorMessage", dialog.errorMessage)
        }
      }
      putJsonArray("incoming") {
        state.receivingMessages.forEach { (id, update) ->
          add(
            buildJsonObject {
              put("receiveId", id)
              putNullable("deviceId", update.device?.deviceId)
              putNullable("deviceName", update.device?.name)
              putNullable("status", update.status::class.simpleName)
              put("pendingAuth", update.status is ReceiveMessageStatus.PendingAuthorization)
            },
          )
        }
      }
      putJsonArray("notifications") {
        state.notifications.forEach { n ->
          add(
            buildJsonObject {
              put("id", n.id)
              putNullable("type", n::class.simpleName)
            },
          )
        }
      }
    }.toString()
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(key: String, value: String?) {
  if (value == null) put(key, JsonNull) else put(key, value)
}

private fun TrustStatus.label(): String = when (this) {
  TrustStatus.Trusted -> "trusted"
  TrustStatus.Untrusted -> "untrusted"
  TrustStatus.Pairing -> "pairing"
  TrustStatus.Unknown -> "unknown"
}

private fun Reachability.label(): String = when (this) {
  Reachability.Reachable -> "reachable"
  Reachability.Unreachable -> "unreachable"
  Reachability.Probing -> "probing"
  Reachability.Unknown -> "unknown"
}

private fun queryParam(query: String, key: String): String? {
  if (query.isEmpty()) return null
  return query.split('&').firstNotNullOfOrNull { part ->
    val eq = part.indexOf('=')
    if (eq < 0) null
    else if (part.substring(0, eq) == key) part.substring(eq + 1) else null
  }
}
