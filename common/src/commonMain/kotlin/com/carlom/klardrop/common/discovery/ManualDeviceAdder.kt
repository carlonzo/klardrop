package com.carlom.klardrop.common.discovery

import com.carlom.klardrop.common.qrshare.LanAddressSelector
import kotlinx.coroutines.flow.StateFlow

public data class ManualAddInfo(
  public val code: String,
  public val address: String,
  public val port: Int,
  public val qrPayload: String,
)

public sealed class ManualAddResult {
  public data class Added(public val deviceName: String) : ManualAddResult()
  public data class AlreadyVisible(public val deviceName: String) : ManualAddResult()
  public data object ThisDevice : ManualAddResult()
  public data object WrongDevice : ManualAddResult()
  public data class Unreachable(public val address: String) : ManualAddResult()
  public data object InvalidInput : ManualAddResult()
  public data object NoLanAddress : ManualAddResult()
}

public class ManualDeviceAdder internal constructor(
  private val lanAddressSelector: LanAddressSelector,
  private val currentDeviceProvider: CurrentDeviceProvider,
  private val serverPort: StateFlow<Int>,
  private val defaultListenPort: Int,
  private val tailnetDiscovery: TailnetDiscovery?,
) {

  public suspend fun thisDeviceInfo(): ManualAddInfo? {
    val lan = lanAddressSelector.selectManualLanAddress() ?: return null
    val livePort = serverPort.value.takeIf { it > 0 } ?: defaultListenPort
    val shortDeviceId = currentDeviceProvider.get().shortDeviceId
    val code = lanCode(lan.address, lan.prefixLength)
    val qrPayload = encodeManualAddPayload(shortDeviceId, lan.allAddresses, livePort)
    return ManualAddInfo(
      code = code,
      address = lan.address,
      port = livePort,
      qrPayload = qrPayload,
    )
  }

  public suspend fun add(input: String): ManualAddResult {
    val ownIps = lanAddressSelector.selectManualLanAddress()?.allAddresses.orEmpty()
    val target = parseManualInput(input, ownIps, defaultListenPort)
    return when (target) {
      is ManualTarget.Invalid -> ManualAddResult.InvalidInput
      is ManualTarget.NoLanAddress -> ManualAddResult.NoLanAddress
      is ManualTarget.Target -> {
        val td = tailnetDiscovery
          ?: return ManualAddResult.Unreachable("${target.addresses.first()}:${target.port}")
        td.addManually(target.addresses, target.port, target.expectedDeviceId)
      }
    }
  }
}
