package com.carlom.klardrop.common.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ManualAddTest {

  @Test
  fun lanCodeTableMatchesSpec() {
    // Examples from spec:
    // ("192.168.1.37", 24) -> "37"
    // ("10.20.12.37", 16) -> "12.37"
    // ("10.20.12.37", 20) -> "12.37"
    // ("10.20.12.37", 8) -> "20.12.37"
    // ("10.20.12.37", null) -> "12.37"
    // ("10.20.12.37", 30) -> "37"
    assertEquals("37", lanCode("192.168.1.37", 24))
    assertEquals("12.37", lanCode("10.20.12.37", 16))
    assertEquals("12.37", lanCode("10.20.12.37", 20))
    assertEquals("20.12.37", lanCode("10.20.12.37", 8))
    assertEquals("12.37", lanCode("10.20.12.37", null))
    assertEquals("37", lanCode("10.20.12.37", 30))
    // Invalid / out of range prefix -> 2 octets
    assertEquals("12.37", lanCode("10.20.12.37", -1))
    assertEquals("12.37", lanCode("10.20.12.37", 33))
  }

  @Test
  fun parseManualInputCodesWithoutPort() {
    val ownIp = "192.168.1.100"
    val defaultPort = 47470

    // 1 octet
    val target1 = parseManualInput("37", listOf(ownIp), defaultPort)
    assertIs<ManualTarget.Target>(target1)
    assertEquals(listOf("192.168.1.37"), target1.addresses)
    assertEquals(defaultPort, target1.port)
    assertNull(target1.expectedDeviceId)

    // 2 octets
    val target2 = parseManualInput("12.37", listOf(ownIp), defaultPort)
    assertIs<ManualTarget.Target>(target2)
    assertEquals(listOf("192.168.12.37"), target2.addresses)
    assertEquals(defaultPort, target2.port)
    assertNull(target2.expectedDeviceId)

    // 3 octets
    val target3 = parseManualInput("20.12.37", listOf(ownIp), defaultPort)
    assertIs<ManualTarget.Target>(target3)
    assertEquals(listOf("192.20.12.37"), target3.addresses)
    assertEquals(defaultPort, target3.port)
    assertNull(target3.expectedDeviceId)

    // Whitespace trimming
    val trimmed = parseManualInput(" 37 ", listOf(ownIp), defaultPort)
    assertIs<ManualTarget.Target>(trimmed)
    assertEquals(listOf("192.168.1.37"), trimmed.addresses)
    assertEquals(defaultPort, trimmed.port)
  }

  @Test
  fun parseManualInputCodesWithPort() {
    val ownIp = "10.0.0.1"
    val target = parseManualInput("37:5000", listOf(ownIp), 47470)
    assertIs<ManualTarget.Target>(target)
    assertEquals(listOf("10.0.0.37"), target.addresses)
    assertEquals(5000, target.port)
  }

  @Test
  fun parseManualInputFullIpWithAndWithoutPort() {
    val defaultPort = 47470

    // Without port (ownIp not needed / can be null)
    val targetNoPort = parseManualInput("10.20.12.37", emptyList(), defaultPort)
    assertIs<ManualTarget.Target>(targetNoPort)
    assertEquals(listOf("10.20.12.37"), targetNoPort.addresses)
    assertEquals(defaultPort, targetNoPort.port)
    assertNull(targetNoPort.expectedDeviceId)

    // With port
    val targetWithPort = parseManualInput("10.20.12.37:8080", emptyList(), defaultPort)
    assertIs<ManualTarget.Target>(targetWithPort)
    assertEquals(listOf("10.20.12.37"), targetWithPort.addresses)
    assertEquals(8080, targetWithPort.port)
    assertNull(targetWithPort.expectedDeviceId)
  }

  @Test
  fun parseManualInputQrPayload() {
    val encoded = encodeManualAddPayload("dev12345", listOf("192.168.1.50", "10.0.0.2"), 5555)
    assertEquals("klardrop://add?v=1&id=dev12345&ip=192.168.1.50,10.0.0.2&port=5555", encoded)

    // Parse valid QR payload with id and multiple IPs
    val target = parseManualInput(encoded, emptyList(), 47470)
    assertIs<ManualTarget.Target>(target)
    assertEquals(listOf("192.168.1.50", "10.0.0.2"), target.addresses)
    assertEquals(5555, target.port)
    assertEquals("dev12345", target.expectedDeviceId)

    // Forward compatibility: unknown query params ignored
    val withExtra = "$encoded&foo=bar&extra=123"
    val targetExtra = parseManualInput(withExtra, emptyList(), 47470)
    assertIs<ManualTarget.Target>(targetExtra)
    assertEquals(listOf("192.168.1.50", "10.0.0.2"), targetExtra.addresses)
    assertEquals(5555, targetExtra.port)
    assertEquals("dev12345", targetExtra.expectedDeviceId)

    // QR payload without optional id
    val noId = "klardrop://add?v=1&ip=192.168.1.50&port=5555"
    val targetNoId = parseManualInput(noId, emptyList(), 47470)
    assertIs<ManualTarget.Target>(targetNoId)
    assertEquals(listOf("192.168.1.50"), targetNoId.addresses)
    assertEquals(5555, targetNoId.port)
    assertNull(targetNoId.expectedDeviceId)

    // Higher/unknown version -> Invalid
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=2&ip=192.168.1.50&port=5555", emptyList(), 47470))
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=0&ip=192.168.1.50&port=5555", emptyList(), 47470))
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://other?v=1&ip=192.168.1.50&port=5555", emptyList(), 47470))
  }

  @Test
  fun parseManualInputInvalidInputs() {
    val ownIp = "192.168.1.100"
    val port = 47470

    assertEquals(ManualTarget.Invalid, parseManualInput("", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("   ", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("256", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("1.2.3.4.5", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("abc", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("37:0", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("37:70000", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("37:port", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("37:5000:6000", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("192.168.1.256", listOf(ownIp), port))
    assertEquals(ManualTarget.Invalid, parseManualInput("-1", listOf(ownIp), port))
  }

  @Test
  fun parseManualInputNoLanAddressWhenOwnIpNullAndInputIsCode() {
    assertEquals(ManualTarget.NoLanAddress, parseManualInput("37", emptyList(), 47470))
    assertEquals(ManualTarget.NoLanAddress, parseManualInput("12.37", emptyList(), 47470))
    assertEquals(ManualTarget.NoLanAddress, parseManualInput("20.12.37", emptyList(), 47470))
    assertEquals(ManualTarget.NoLanAddress, parseManualInput("37:5000", emptyList(), 47470))
  }

  @Test
  fun leadingZerosAreNormalisedNotPassedThrough() {
    val own = "192.168.1.100"
    assertEquals(listOf("192.168.1.37"), (parseManualInput("192.168.1.037", listOf(own), 1) as ManualTarget.Target).addresses)
    assertEquals(listOf("192.168.1.37"), (parseManualInput("037", listOf(own), 1) as ManualTarget.Target).addresses)
    assertEquals(47, (parseManualInput("37:047", listOf(own), 1) as ManualTarget.Target).port)
    val qr = parseManualInput("klardrop://add?v=1&ip=010.000.000.007&port=0055", emptyList(), 1) as ManualTarget.Target
    assertEquals(listOf("10.0.0.7"), qr.addresses)
    assertEquals(55, qr.port)
  }

  @Test
  fun nonAsciiDigitsAreInvalid() {
    val own = "192.168.1.100"
    assertEquals(ManualTarget.Invalid, parseManualInput("\u0663\u0667", listOf(own), 1)) // Arabic-Indic "37"
    assertEquals(ManualTarget.Invalid, parseManualInput("37:\u0665\u0660", listOf(own), 1))
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=1&ip=192.168.1.\u0663\u0667&port=5555", emptyList(), 1))
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=1&ip=192.168.1.37&port=\u0665\u0665", emptyList(), 1))
  }

  @Test
  fun qrIpListIsCappedAtFour() {
    val ips = (1..8).joinToString(",") { "10.0.0.$it" }
    val target = parseManualInput("klardrop://add?v=1&ip=$ips&port=5555", emptyList(), 1) as ManualTarget.Target
    assertEquals((1..4).map { "10.0.0.$it" }, target.addresses)
  }

  @Test
  fun codeIsCombinedWithEveryOwnAddress() {
    val target = parseManualInput("37", listOf("192.168.43.1", "10.20.1.5", "192.168.43.1"), 47470) as ManualTarget.Target
    assertEquals(listOf("192.168.43.37", "10.20.1.37"), target.addresses)
    assertEquals(ManualTarget.NoLanAddress, parseManualInput("37", emptyList(), 47470))
  }

  @Test
  fun qrSkipsInvalidIpsAndRejectsWhenNoneValid() {
    val target = parseManualInput("klardrop://add?v=1&ip=bogus,10.0.0.2&port=5555", emptyList(), 1) as ManualTarget.Target
    assertEquals(listOf("10.0.0.2"), target.addresses)
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=1&ip=bogus&port=5555", emptyList(), 1))
    assertEquals(ManualTarget.Invalid, parseManualInput("klardrop://add?v=1&ip=&port=5555", emptyList(), 1))
  }

  @Test
  fun qrIpsAreDedupedBeforeTheCapAndSchemeIsCaseInsensitive() {
    val ips = "10.0.0.1,010.0.0.1,10.0.0.2,10.0.0.2,10.0.0.3,10.0.0.4,10.0.0.5"
    val target = parseManualInput("KLARDROP://Add?v=1&ip=$ips&port=5555", emptyList(), 1) as ManualTarget.Target
    assertEquals(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4"), target.addresses)
  }
}
