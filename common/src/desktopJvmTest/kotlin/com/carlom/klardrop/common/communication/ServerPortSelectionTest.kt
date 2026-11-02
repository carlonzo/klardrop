package com.carlom.klardrop.common.communication

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ServerPortSelectionTest {

  private fun freePort(): Int = ServerSocket(0).use { it.localPort }

  @Test
  fun bindsTheFirstPreferredPortWhenFree() = runBlocking {
    val port = freePort()
    val server = createTestServer()
    try {
      assertEquals(port, server.startServer(listOf(port)).port)
    } finally {
      server.stopServer()
    }
  }

  // SO_REUSEPORT would let a second Klardrop process share the default port and split inbound
  // connections between the two; the second one must move to its fallback instead.
  @Test
  fun secondServerDoesNotShareAPortHeldByAnotherServer() = runBlocking {
    val port = freePort()
    val fallback = freePort()
    val first = createTestServer()
    val second = createTestServer()
    try {
      assertEquals(port, first.startServer(listOf(port, fallback)).port)
      assertEquals(fallback, second.startServer(listOf(port, fallback)).port)
    } finally {
      first.stopServer()
      second.stopServer()
    }
  }

  @Test
  fun fallsBackToAnEphemeralPortWhenAllPreferredAreTaken() = runBlocking {
    ServerSocket(0).use { held ->
      val server = createTestServer()
      try {
        assertNotEquals(held.localPort, server.startServer(listOf(held.localPort)).port)
      } finally {
        server.stopServer()
      }
    }
  }
}
