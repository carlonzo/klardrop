package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.DeviceConnection
import com.carlom.klardrop.common.discovery.DeviceInfo
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class NearbyShare(
  private val serviceDiscoveryMdns: ServiceDiscoveryMdns,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val visibleDevices: VisibleDevices,
  coroutines: Coroutines
) {

  private val nearbyShareScope = CoroutineScope(coroutines.mainDispatcher)
  private val currentDevice = nearbyShareScope.async(coroutines.ioDispatcher) { currentDeviceProvider.get() }

  private var publishingJob: Job? = null

  fun startDiscovery() {

    nearbyShareScope.launch {

      serviceDiscoveryMdns.discoverServices(NEARBY_SERVICE_TYPE).collect {

        log("NearbyShare", "New discovery event: $it")

        when (it) {


          is ServiceDiscoveryEvent.ServiceFound -> it.serviceInfo.addresses.forEach { address ->
            if (isValidService(it.serviceInfo)) {
              visibleDevices.onNewDeviceVisible(it.serviceInfo.toDeviceInfo(), DeviceConnection.Nearby(address, it.serviceInfo.port))
            } else {
              log("NearbyShare", "Invalid service discovered: ${it.serviceInfo}. Will skip")
            }
          }


          is ServiceDiscoveryEvent.ServiceLost -> {

            if (it.serviceInfo.addresses.isNotEmpty()) {
              it.serviceInfo.addresses.forEach { address ->
                visibleDevices.onDeviceLost(it.serviceInfo.serviceName, DeviceConnection.Nearby(address, it.serviceInfo.port))
              }
            } else {
              visibleDevices.onDeviceLost(it.serviceInfo.serviceName)
            }

          }

        }

      }
    }

  }

  private fun isValidService(serviceInfo: ServiceInfo): Boolean {
    return (serviceInfo.serviceType == NEARBY_SERVICE_TYPE || serviceInfo.serviceType == NEARBY_SERVICE_TYPE_LOCAL) && serviceInfo.addresses.isNotEmpty() && serviceInfo.attributes.isNotEmpty()
  }

  fun startPublishingService(port: Int) {
    publishingJob?.cancel()
    publishingJob = nearbyShareScope.launch {
      serviceDiscoveryMdns.registerService(
        buildServiceInfo(port)
      )
    }
  }

  private fun ServiceInfo.toDeviceInfo(): DeviceInfo {

    val endpointInfo = attributes.getValue("n")
    val endpointInfoBytes = urlSafeBase64DecodeString(endpointInfo)

    // 1 byte: Version(3 bits)|Visibility(1 bit)|Device Type(3 bits)|Reserved(1 bits)
    // Device types: unknown=0, phone=1, tablet=2, laptop=3
    val deviceInfoByte = endpointInfoBytes[0].toInt()

    val deviceTypeId = deviceInfoByte and 0b0000_1110
    val deviceType = deviceTypeFromId(deviceTypeId shr 1) // 0000 ddd0 (d == devicetype)
    val deviceNameLength = endpointInfoBytes[17]
    val deviceName = endpointInfoBytes.sliceArray(18 until 18 + deviceNameLength.toInt()).decodeToString()


    return DeviceInfo(
      name = deviceName,
      deviceId = serviceName,
      deviceType = deviceType,
    )
  }

  private suspend fun buildServiceInfo(port: Int): RegisterServiceInfo {
    val currentDevice = currentDevice.await()

    val nameBytes = byteArrayOf(
      0x23.toByte(), // PCP
      *getDeviceId(currentDevice), // 4 bytes unique device id
      0xFC.toByte(), 0x9F.toByte(), 0x5E.toByte(), // Service ID hash
      0.toByte(), 0.toByte(),
    )

    val endpointInfo = createEndpointInfo(currentDevice)

    // urlsafe base64
    val name = urlSafeBase64EncodedString(nameBytes)

    return RegisterServiceInfo(
      port = port,
      serviceName = name,
      serviceType = NEARBY_SERVICE_TYPE,
      attributes = mapOf("n" to urlSafeBase64EncodedString(endpointInfo))
    )
  }

  private fun getDeviceId(currentDevice: CurrentDevice): ByteArray {
    val deviceId = currentDevice.deviceId

    return buildString {

      deviceId.forEach {
        while (length < 4) {
          if (it.isLetterOrDigit()) {
            append(it)
          }
        }
      }

    }.encodeToByteArray()
  }

  private fun deviceTypeFromId(id: Int): DeviceType {
    return when (id) {
      1 -> DeviceType.MOBILE
      2 -> DeviceType.TABLET
      3 -> DeviceType.DESKTOP
      else -> {
        log("NearbyShare", "Unknown device type id: $id")
        DeviceType.MOBILE
      }
    }
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun urlSafeBase64EncodedString(data: ByteArray): String {
    return Base64.encode(data).map {
      when (it) {
        '+' -> '-'
        '/' -> '_'
        '=' -> ""
        else -> it
      }
    }.joinToString(separator = "")
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun urlSafeBase64DecodeString(data: String): ByteArray {
    return data.map {
      when (it) {
        '-' -> '+'
        '_' -> '/'
        else -> it
      }
    }.joinToString(separator = "").let {
      Base64.decode(it.encodeToByteArray())
    }
  }

  private companion object {
    const val NEARBY_SERVICE_TYPE = "_FC9F5ED42C8A._tcp."
    private const val NEARBY_SERVICE_TYPE_LOCAL = "_FC9F5ED42C8A._tcp.local."
  }

}