package com.carlom.klardrop.common.discovery

import FakeLocalPropertiesRepository
import com.carlom.klardrop.common.qrshare.LanAddressSelector
import com.carlom.klardrop.common.qrshare.ManualLanAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ManualDeviceAdderTest {

  private class FakeSelector(var manualAddress: ManualLanAddress? = null) : LanAddressSelector {
    override suspend fun selectIpv4(): String? = manualAddress?.address
    override suspend fun selectManualLanAddress(): ManualLanAddress? = manualAddress
    override fun observeChanges(): Flow<String?> = emptyFlow()
  }

  @Test
  fun thisDeviceInfoReturnsNullWhenNoLanAddress() = runTest {
    val adder = ManualDeviceAdder(
      lanAddressSelector = FakeSelector(null),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(47470),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )
    assertNull(adder.thisDeviceInfo())
  }

  @Test
  fun thisDeviceInfoConstructsCorrectManualAddInfo() = runTest {
    val selector = FakeSelector(
      ManualLanAddress(
        address = "192.168.1.37",
        prefixLength = 24,
        allAddresses = listOf("192.168.1.37", "10.0.0.1"),
      )
    )
    val adder = ManualDeviceAdder(
      lanAddressSelector = selector,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(50000),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )

    val info = adder.thisDeviceInfo()
    assertEquals("37", info?.code)
    assertEquals("192.168.1.37", info?.address)
    assertEquals(50000, info?.port)
    assertEquals("klardrop://add?v=1&id=selfid01&ip=192.168.1.37,10.0.0.1&port=50000", info?.qrPayload)
  }

  @Test
  fun thisDeviceInfoFallsBackToDefaultListenPortWhenServerPortIsZero() = runTest {
    val selector = FakeSelector(
      ManualLanAddress(
        address = "192.168.1.37",
        prefixLength = 24,
        allAddresses = listOf("192.168.1.37"),
      )
    )
    val adder = ManualDeviceAdder(
      lanAddressSelector = selector,
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(0),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )

    val info = adder.thisDeviceInfo()
    assertEquals(47470, info?.port)
  }

  @Test
  fun addReturnsSensibleErrorWhenTailnetDiscoveryIsNull() = runTest {
    val adder = ManualDeviceAdder(
      lanAddressSelector = FakeSelector(ManualLanAddress("192.168.1.37", 24, listOf("192.168.1.37"))),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(47470),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )
    val result = adder.add("37")
    assertEquals(ManualAddResult.Unreachable("192.168.1.37:47470"), result)
  }

  @Test
  fun addReturnsInvalidInputOnMalformedInput() = runTest {
    val adder = ManualDeviceAdder(
      lanAddressSelector = FakeSelector(ManualLanAddress("192.168.1.37", 24, listOf("192.168.1.37"))),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(47470),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )
    val result = adder.add("invalid:input:here")
    assertEquals(ManualAddResult.InvalidInput, result)
  }

  @Test
  fun addReturnsNoLanAddressWhenInputIsCodeAndNoLanAddressAvailable() = runTest {
    val adder = ManualDeviceAdder(
      lanAddressSelector = FakeSelector(null),
      currentDeviceProvider = CurrentDeviceProvider(FakeLocalPropertiesRepository("selfid01")),
      serverPort = MutableStateFlow(47470),
      defaultListenPort = 47470,
      tailnetDiscovery = null,
    )
    val result = adder.add("37")
    assertEquals(ManualAddResult.NoLanAddress, result)
  }
}
