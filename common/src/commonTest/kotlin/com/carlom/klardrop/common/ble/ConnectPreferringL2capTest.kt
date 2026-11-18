package com.carlom.klardrop.common.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectPreferringL2capTest {

  private class FakeSession(override val transport: BleSessionTransport) : BleSession {
    override val deviceId = "peer"
    override val isOpen = true
    override val mtu = 20
    override suspend fun sendChunk(chunk: ByteArray) {}
    override suspend fun receiveChunk(): ByteArray? = null
    override fun close() {}
  }

  private val gatt = FakeSession(BleSessionTransport.GATT)
  private val l2cap = FakeSession(BleSessionTransport.L2CAP)
  private var gattCalls = 0
  private var l2capCalls = 0
  private val openGatt: suspend () -> BleSession = { gattCalls++; gatt }

  @Test
  fun noPsmUsesGatt() = runTest {
    val s = connectPreferringL2cap("p", { null }, { l2capCalls++; l2cap }, openGatt)
    assertSame(gatt, s)
    assertEquals(0, l2capCalls)
  }

  @Test
  fun l2capSuccess() = runTest {
    val s = connectPreferringL2cap("p", { 0x81 }, { assertEquals(0x81, it); l2cap }, openGatt)
    assertSame(l2cap, s)
    assertEquals(0, gattCalls)
  }

  @Test
  fun l2capThrowsFallsBack() = runTest {
    val s = connectPreferringL2cap("p", { 0x81 }, { throw IllegalStateException("boom") }, openGatt)
    assertSame(gatt, s)
  }

  @Test
  fun l2capHangsFallsBackAndCancelsAttempt() = runTest {
    var cancelled = false
    val s = connectPreferringL2cap("p", { 0x81 }, {
      try { awaitCancellation() } finally { cancelled = true }
    }, openGatt, timeoutMs = 1_000)
    assertSame(gatt, s)
    assertTrue(cancelled)
    assertEquals(1_000, testScheduler.currentTime)
  }

  @Test
  fun readPsmHangsFallsBack() = runTest {
    val s = connectPreferringL2cap("p", { awaitCancellation() }, { l2cap }, openGatt, timeoutMs = 1_000)
    assertSame(gatt, s)
  }

  @Test
  fun callerCancellationDoesNotFallBack() = runTest {
    val job = async(start = CoroutineStart.UNDISPATCHED) {
      connectPreferringL2cap("p", { 0x81 }, { awaitCancellation() }, openGatt, timeoutMs = 5_000)
    }
    advanceTimeBy(1_000)
    runCurrent()
    assertFalse(job.isCompleted)
    job.cancel()
    assertFailsWith<CancellationException> { job.await() }
    assertEquals(0, gattCalls)
  }
}
