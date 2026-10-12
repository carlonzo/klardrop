package com.carlom.klardrop.common.network

import com.carlom.klardrop.common.qrshare.PlatformLanAddressSelector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxNetworkInterfacesTest {

    @Test
    fun getifaddrsReturnsNonLoopbackIpv4WhenAvailable() {
        val ifaces = getLinuxNetworkInterfaces()
        println("Found ${ifaces.size} interfaces via getifaddrs: ${ifaces.map { "${it.name} (up=${it.isUp}, loopback=${it.isLoopback}, addrs=${it.ipv4Addresses})" }}")

        // None of the returned addresses should be 127.0.0.1 or loopback
        for (iface in ifaces) {
            for (addr in iface.ipv4Addresses) {
                assertFalse(addr.startsWith("127."), "Address $addr on ${iface.name} should not be loopback")
                assertTrue(addr.split('.').size == 4, "Address $addr should be dotted-quad IPv4")
            }
        }

        val nonLoopbackWithAddrs = ifaces.filter { !it.isLoopback && it.ipv4Addresses.isNotEmpty() }
        if (nonLoopbackWithAddrs.isEmpty()) {
            println("NOTE: No non-loopback interface with IPv4 address available on this host.")
        } else {
            val nonLoopbackIpv4 = nonLoopbackWithAddrs.flatMap { it.ipv4Addresses }.firstOrNull()
            assertNotNull(nonLoopbackIpv4, "Should find at least one non-loopback IPv4 address")
            println("Verified non-loopback IPv4 via getifaddrs: $nonLoopbackIpv4 on ${nonLoopbackWithAddrs.first().name}")
        }
    }

    @Test
    fun lanAddressSelectorSelectsIpv4() = runBlocking {
        val selector = PlatformLanAddressSelector()
        val selected = selector.selectIpv4()
        println("LanAddressSelector selected IP: $selected")
        if (selected != null) {
            assertFalse(selected.startsWith("127."))
            assertTrue(selected.split('.').size == 4)
        }
    }
}
