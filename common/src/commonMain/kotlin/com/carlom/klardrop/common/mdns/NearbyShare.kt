package com.carlom.klardrop.common.mdns

import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.VisibleDevices
import com.carlom.klardrop.common.persistence.DeviceInfo
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

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

      serviceDiscoveryMdns.discoverServices(serviceType).collect {

        it.map { serviceInfo ->
          serviceInfo.toDeviceInfo()
        }.forEach { deviceInfo ->
          visibleDevices.onNewDeviceVisible(deviceInfo)
        }

      }
    }

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

    val deviceType = deviceTypeFromId(endpointInfoBytes[0].toInt() shr 3)
    val deviceNameLength = endpointInfoBytes[17]
    val deviceName = endpointInfoBytes.sliceArray(18 until 18 + deviceNameLength.toInt()).decodeToString()


    return DeviceInfo(
      name = deviceName,
      deviceId = serviceName,
      deviceType = deviceType,
      lastAddress = address ?: "",
    )
  }

  private suspend fun buildServiceInfo(port: Int): ServiceInfo {
    val currentDevice = currentDevice.await()

    val nameBytes = byteArrayOf(
      0x23.toByte(), // PCP
      *getDeviceEndpoint(currentDevice), // 4 bytes unique device id
      0xFC.toByte(), 0x9F.toByte(), 0x5E.toByte(), // Service ID hash
      0.toByte(), 0.toByte(),
    )


    // urlsafe base64
    val name = urlSafeBase64EncodedString(nameBytes)
    val deviceName = currentDevice.deviceName

    val endpointInfo = byteArrayOf(
      (deviceTypeId() shl 1).toByte(), // 0000 ddd0 (d == devicetype)
      *Random.nextBytes(16), // 16 bytes random
      deviceName.length.toByte(),
      *deviceName.encodeToByteArray()
    )

    return ServiceInfo(
      port = port,
      serviceName = name,
      serviceType = serviceType,
      attributes = mapOf("n" to urlSafeBase64EncodedString(endpointInfo))
    )
  }

  private fun getDeviceEndpoint(currentDevice: CurrentDevice): ByteArray {
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

  private suspend fun deviceTypeId(): Int {
    return when (currentDevice.await().deviceType) {
      DeviceType.MOBILE -> 1
      DeviceType.TABLET -> 2
      DeviceType.DESKTOP -> 3
//      else -> 0
    }
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
    const val serviceType = "_FC9F5ED42C8A._tcp."
  }

}