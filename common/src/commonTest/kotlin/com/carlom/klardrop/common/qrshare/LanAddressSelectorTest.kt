package com.carlom.klardrop.common.qrshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanAddressSelectorTest {

  @Test
  fun cellularOnlyReturnsNull() {
    // rmnet0 10.1.2.3 only → null
    val result = selectLanAddress(listOf("rmnet0" to "10.1.2.3"))
    assertNull(result)
  }

  @Test
  fun staWifiBeatsLeftoverHotspotAddress() {
    val result = selectLanAddress(
      listOf(
        "ap0" to "192.168.43.1",
        "wlan0" to "10.79.71.196",
      )
    )
    assertEquals("10.79.71.196", result)
  }

  @Test
  fun cellularPlusHotspotPicksHotspot() {
    // rmnet0 10.1.2.3 + ap0 192.168.43.1 → 192.168.43.1
    val result = selectLanAddress(
      listOf(
        "rmnet0" to "10.1.2.3",
        "ap0" to "192.168.43.1",
      )
    )
    assertEquals("192.168.43.1", result)
  }

  @Test
  fun vpnPlusWifiPicksWifi() {
    // tun0 10.8.0.2 + wlan0 192.168.1.5 → 192.168.1.5
    val result = selectLanAddress(
      listOf(
        "tun0" to "10.8.0.2",
        "wlan0" to "192.168.1.5",
      )
    )
    assertEquals("192.168.1.5", result)
  }

  @Test
  fun loopbackOnlyReturnsNull() {
    // loopback only → null
    assertNull(selectLanAddress(listOf("lo" to "127.0.0.1")))
    assertNull(selectLanAddress(listOf("lo0" to "127.0.0.1")))
    assertNull(selectLanAddress(listOf("wlan0" to "127.0.0.1")))
  }

  @Test
  fun linkLocalOnWifiDropped() {
    // 169.254.1.2 wlan0 → null (link-local dropped)
    val result = selectLanAddress(listOf("wlan0" to "169.254.1.2"))
    assertNull(result)
  }

  @Test
  fun cgnatDropped() {
    // 100.64.1.2 (CGNAT) → null
    assertNull(selectLanAddress(listOf("wlan0" to "100.64.1.2")))
    assertNull(selectLanAddress(listOf("tailscale0" to "100.64.1.2")))
    assertNull(selectLanAddress(listOf("eth0" to "100.100.1.2")))
  }

  @Test
  fun ethernetOnlyPicksEthernet() {
    // eth0 192.168.1.10 only → that address
    val result = selectLanAddress(listOf("eth0" to "192.168.1.10"))
    assertEquals("192.168.1.10", result)
  }

  @Test
  fun wifiWinsOverEthernetRegardlessOfOrder() {
    // wlan0 192.168.1.5 + eth0 192.168.1.10 → wlan (Wi-Fi wins)
    val forward = selectLanAddress(
      listOf(
        "wlan0" to "192.168.1.5",
        "eth0" to "192.168.1.10",
      )
    )
    assertEquals("192.168.1.5", forward)

    val reversed = selectLanAddress(
      listOf(
        "eth0" to "192.168.1.10",
        "wlan0" to "192.168.1.5",
      )
    )
    assertEquals("192.168.1.5", reversed)
  }

  @Test
  fun hotspotOnOddInterfaceNamePlusCellularPicksHotspot() {
    // hotspot 172.20.10.1 on odd iface name + cellular → hotspot
    val result = selectLanAddress(
      listOf(
        "bridge100" to "172.20.10.1",
        "rmnet0" to "10.1.2.3",
      )
    )
    assertEquals("172.20.10.1", result)
  }

  @Test
  fun windowsHotspotOnOddInterfaceNamePicksHotspot() {
    // hotspot 192.168.137.1 similarly
    val result = selectLanAddress(
      listOf(
        "custom_adapter" to "192.168.137.1",
        "rmnet_data0" to "10.2.3.4",
      )
    )
    assertEquals("192.168.137.1", result)
  }

  @Test
  fun publicIpOnWifiReturnsNull() {
    // public 8.8.8.8 on wlan → null (not RFC1918)
    val result = selectLanAddress(listOf("wlan0" to "8.8.8.8"))
    assertNull(result)
  }

  @Test
  fun ipv6AndUnspecifiedDropped() {
    assertNull(selectLanAddress(listOf("wlan0" to "fe80::1")))
    assertNull(selectLanAddress(listOf("wlan0" to "2001:db8::1")))
    assertNull(selectLanAddress(listOf("wlan0" to "0.0.0.0")))
  }

  @Test
  fun allCellularPrefixesDropped() {
    assertNull(selectLanAddress(listOf("ccmni0" to "10.1.2.3")))
    assertNull(selectLanAddress(listOf("v4-rmnet0" to "10.1.2.3")))
    assertNull(selectLanAddress(listOf("pdp0" to "10.1.2.3")))
    assertNull(selectLanAddress(listOf("wwan0" to "10.1.2.3")))
    assertNull(selectLanAddress(listOf("rmnet_data1" to "10.1.2.3")))
  }

  @Test
  fun allTunnelPrefixesDropped() {
    assertNull(selectLanAddress(listOf("utun2" to "10.8.0.1")))
    assertNull(selectLanAddress(listOf("wg0" to "10.0.0.1")))
    assertNull(selectLanAddress(listOf("ppp0" to "10.0.0.1")))
    assertNull(selectLanAddress(listOf("tailscale0" to "10.0.0.1")))
    assertNull(selectLanAddress(listOf("ipsec0" to "10.0.0.1")))
  }

  @Test
  fun variousWifiAndEthernetPrefixesSupported() {
    assertEquals("192.168.1.2", selectLanAddress(listOf("en0" to "192.168.1.2")))
    assertEquals("192.168.1.3", selectLanAddress(listOf("swlan0" to "192.168.1.3")))
    assertEquals("192.168.1.4", selectLanAddress(listOf("wlp2s0" to "192.168.1.4")))
    assertEquals("192.168.1.5", selectLanAddress(listOf("en1" to "192.168.1.5")))
    assertEquals("192.168.1.6", selectLanAddress(listOf("lan0" to "192.168.1.6")))
  }

  @Test
  fun nonHotspotSubnetOnOddInterfaceRejectedWithoutFallback() {
    assertNull(selectLanAddress(listOf("docker0" to "172.17.0.1")))
    assertNull(selectLanAddress(listOf("unknown_nic" to "10.0.0.5")))
  }

  @Test
  fun selectLanIpv4AliasWorks() {
    val result = selectLanIpv4(listOf("wlan0" to "192.168.1.5"))
    assertEquals("192.168.1.5", result)
  }
}
