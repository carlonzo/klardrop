package com.carlom.klardrop.common.persistence

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals

class KnownEndpointsRepositoryTest {

  @Serializable
  private data class OldKnownEndpoint(
    val deviceId: String,
    val port: Int,
    val tailnetAddresses: List<String> = emptyList(),
    val lastSeenMs: Long = 0,
  )

  @Test
  fun oldFormatDecodesIntoNewKnownEndpointPreservingFields() {
    val old = OldKnownEndpoint(
      deviceId = "peer1234",
      port = 47470,
      tailnetAddresses = listOf("100.64.0.5", "100.64.0.6"),
      lastSeenMs = 123456789L,
    )
    val bytes = ProtoBuf.encodeToByteArray(old)
    val decoded = ProtoBuf.decodeFromByteArray<KnownEndpoint>(bytes)
    assertEquals("peer1234", decoded.deviceId)
    assertEquals(47470, decoded.port)
    assertEquals(listOf("100.64.0.5", "100.64.0.6"), decoded.tailnetAddresses)
    assertEquals(123456789L, decoded.lastSeenMs)
    assertEquals(emptyList(), decoded.lanAddresses)
    assertEquals(0L, decoded.lanAddressesSeenMs)
  }
}
