package com.carlom.klardrop.cli.commands

import com.carlom.klardrop.common.discovery.DiscoveryDevice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Shared JSON model + encoder for CLI `--json` output. Default [Json] omits null fields. */
val cliJson = Json

@Serializable
data class ConnJson(val type: String, val address: String, val port: Int)

@Serializable
data class DeviceJson(
  val device_id: String,
  val name: String,
  val device_type: String,
  val os_type: String,
  val connections: List<ConnJson>,
)

@Serializable
data class StatusJson(
  val running: Boolean,
  val debug: Boolean,
  val device_count: Int,
  val devices: List<DeviceJson>,
)

@Serializable
data class ReceivedJson(
  val timestamp_ms: Long,
  val timestamp: String,
  val event: String,
  val sender_id: String,
  val sender_name: String,
  val sender_type: String,
  val type: String,
  val content: String? = null,
  val filename: String? = null,
  val size: Long? = null,
)

fun DiscoveryDevice.toJson(): DeviceJson = DeviceJson(
  device_id = deviceInfo.deviceId,
  name = deviceInfo.name,
  device_type = deviceInfo.deviceType.name,
  os_type = deviceInfo.osType.name,
  connections = deviceConnections.map { ConnJson(it.deviceConnectionType.name, it.address, it.port) },
)
