package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BleSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Peripheral-role logic exercised through a fake [BlueZFacade] that simulates remote
 * centrals subscribing, writing, and unsubscribing — no D-Bus daemon involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinuxBlePeripheralTest {

  /** Fake facade: records exports/notifies and lets tests drive the BlueZ-side callbacks. */
  private class FakeBlueZFacade : BlueZFacade {
    @Volatile var exported = false
    var unregisterCount = 0
      private set
    val notified = mutableListOf<Pair<String, List<Byte>>>()
    val exportCompleted = CompletableDeferred<Unit>()

    @Volatile var writeListener: ((String, ByteArray) -> Unit)? = null
    @Volatile var subscriptionListener: ((String, Boolean) -> Unit)? = null

    override suspend fun probeCapability() = BlueZCapability(true, listOf("/org/bluez/hci0"))

    override suspend fun exportApplication() {
      exported = true
      exportCompleted.complete(Unit)
    }

    override suspend fun unregisterApplication() {
      exported = false
      unregisterCount++
    }

    override suspend fun notifyValue(centralId: String, value: ByteArray) {
      synchronized(notified) { notified.add(centralId to value.toList()) }
    }

    override fun onCharacteristicWrite(listener: ((String, ByteArray) -> Unit)?) {
      writeListener = listener
    }

    override fun onCentralSubscription(listener: ((String, Boolean) -> Unit)?) {
      subscriptionListener = listener
    }

    // Test-side triggers standing in for BlueZ D-Bus events.
    fun centralSubscribes(centralId: String) = subscriptionListener?.invoke(centralId, true)
    fun centralUnsubscribes(centralId: String) = subscriptionListener?.invoke(centralId, false)
    fun centralWrites(centralId: String, value: ByteArray) = writeListener?.invoke(centralId, value)
  }

  /**
   * Starts collecting [peripheral.serveGatt] into [sessions] and suspends until the
   * GATT application is exported. The collector runs until the test cancels it —
   * completing it early (take) would tear the sessions down via flow cleanup.
   */
  private suspend fun CoroutineScope.startServing(
    facade: FakeBlueZFacade,
    peripheral: LinuxBlePeripheral,
    sessions: MutableList<BleSession>,
  ): Job {
    val collector = launch { peripheral.serveGatt().toList(sessions) }
    facade.exportCompleted.await()
    return collector
  }

  /** Suspends until [count] sessions were emitted (the collector runs concurrently). */
  private suspend fun awaitSessions(sessions: List<BleSession>, count: Int) {
    withTimeout(5_000) {
      while (sessions.size < count) yield()
    }
  }

  @Test
  fun twoRemotesSubscribingEmitTwoSessions() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    facade.centralSubscribes("/org/bluez/hci0/dev_A")
    facade.centralSubscribes("/org/bluez/hci0/dev_B")
    awaitSessions(sessions, count = 2)

    assertEquals(
      listOf("/org/bluez/hci0/dev_A", "/org/bluez/hci0/dev_B"),
      sessions.map { it.deviceId },
    )
    assertTrue(sessions.all { it.isOpen })
    assertTrue(sessions.all { it.mtu == BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE })

    collector.cancelAndJoin()
  }

  @Test
  fun writesArriveInOrderViaReceiveChunk() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    facade.centralSubscribes("dev_A")
    awaitSessions(sessions, count = 1)
    val session = sessions.single()

    facade.centralWrites("dev_A", byteArrayOf(1, 2, 3))
    facade.centralWrites("dev_A", byteArrayOf(4, 5))
    facade.centralWrites("dev_A", byteArrayOf(6))

    assertEquals(listOf<Byte>(1, 2, 3), session.receiveChunk()!!.toList())
    assertEquals(listOf<Byte>(4, 5), session.receiveChunk()!!.toList())
    assertEquals(listOf<Byte>(6), session.receiveChunk()!!.toList())

    collector.cancelAndJoin()
  }

  @Test
  fun sendChunkEmitsNotifyToSubscribedCentral() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    facade.centralSubscribes("dev_A")
    awaitSessions(sessions, count = 1)
    val session = sessions.single()

    session.sendChunk(byteArrayOf(9, 8, 7))
    assertEquals(listOf("dev_A" to listOf<Byte>(9, 8, 7)), facade.notified.toList())

    // Contract: oversize chunk and closed session are rejected.
    assertFailsWith<IllegalArgumentException> { session.sendChunk(ByteArray(session.mtu + 1)) }
    session.close()
    assertFailsWith<IllegalStateException> { session.sendChunk(byteArrayOf(1)) }

    collector.cancelAndJoin()
  }

  @Test
  fun remoteUnsubscribeClosesSession() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    facade.centralSubscribes("dev_A")
    awaitSessions(sessions, count = 1)
    val session = sessions.single()
    assertTrue(session.isOpen)

    facade.centralUnsubscribes("dev_A")

    assertFalse(session.isOpen)
    assertNull(session.receiveChunk())

    collector.cancelAndJoin()
  }

  @Test
  fun flowCancellationUnregistersApplication() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    assertTrue(facade.exported)

    collector.cancelAndJoin()

    assertFalse(facade.exported)
    assertEquals(1, facade.unregisterCount)
  }

  @Test
  fun garbageWritesDoNotCrashSession() = runTest {
    val facade = FakeBlueZFacade()
    val peripheral = LinuxBlePeripheral(facade)

    val sessions = mutableListOf<BleSession>()
    val collector = startServing(facade, peripheral, sessions)
    facade.centralSubscribes("dev_A")
    awaitSessions(sessions, count = 1)
    val session = sessions.single()

    // Malformed payloads, an empty write, and a write from an unknown central —
    // framing is the bridge's job, so the session must pipe bytes and stay alive.
    val garbage = byteArrayOf(0xFF.toByte(), 0x00, 0x13, -5)
    facade.centralWrites("dev_A", garbage)
    facade.centralWrites("dev_A", ByteArray(0))
    facade.centralWrites("no-such-central", byteArrayOf(1))
    facade.centralWrites("dev_A", byteArrayOf(42))

    assertEquals(garbage.toList(), session.receiveChunk()!!.toList())
    assertEquals(listOf<Byte>(42), session.receiveChunk()!!.toList())
    assertTrue(session.isOpen)

    collector.cancelAndJoin()
  }

  @Test
  fun exportedGattObjectGraphIsWellFormed() {
    val signals = mutableListOf<Message>()
    val writeSink = mutableListOf<Pair<String, ByteArray>>()
    val subscriptions = mutableListOf<Pair<String, Boolean>>()
    val app = GattApplication(
      onTxWrite = { id, v -> writeSink.add(id to v) },
      onRxSubscribe = { id, s -> subscriptions.add(id to s) },
      emitSignal = { signals.add(it) },
      resolveCentralId = { "dev_A" },
    )

    // Object tree BlueZ walks during RegisterApplication.
    val managed = app.GetManagedObjects()
    assertEquals(setOf(app.servicePath, app.txPath, app.rxPath), managed.keys.map { it.path }.toSet())
    val service = managed.getValue(DBusPath(app.servicePath)).getValue("org.bluez.GattService1")
    assertEquals(BleConstants.SERVICE_UUID, service.getValue("UUID").value)
    assertEquals(true, service.getValue("Primary").value)
    val tx = managed.getValue(DBusPath(app.txPath)).getValue("org.bluez.GattCharacteristic1")
    assertEquals(BleConstants.TX_CHARACTERISTIC_UUID, tx.getValue("UUID").value)
    assertEquals(listOf("write"), tx.getValue("Flags").value)
    val rx = managed.getValue(DBusPath(app.rxPath)).getValue("org.bluez.GattCharacteristic1")
    assertEquals(BleConstants.RX_CHARACTERISTIC_UUID, rx.getValue("UUID").value)
    assertEquals(listOf("read", "notify"), rx.getValue("Flags").value)

    // TX WriteValue routes to the write callback with the device option as central id.
    app.tx.WriteValue(
      byteArrayOf(1, 2),
      mapOf("device" to Variant(DBusPath("/org/bluez/hci0/dev_A"))),
    )
    assertEquals(listOf("/org/bluez/hci0/dev_A" to byteArrayOf(1, 2).toList()), writeSink.map { it.first to it.second.toList() })

    // RX StartNotify/StopNotify route to the subscription callback.
    app.rx.StartNotify()
    app.rx.StopNotify()
    assertEquals(listOf("dev_A" to true, "dev_A" to false), subscriptions)

    // notifySubscribers emits a PropertiesChanged with the new Value.
    app.rx.StartNotify()
    app.notifySubscribers(byteArrayOf(9))
    assertEquals(1, signals.size)
    val changed = signals.single() as Properties.PropertiesChanged
    assertEquals(app.rxPath, changed.path)
    assertEquals("org.bluez.GattCharacteristic1", changed.getInterfaceName())
    assertEquals(byteArrayOf(9).toList(), (changed.getPropertiesChanged().getValue("Value").value as ByteArray).toList())
    assertEquals(byteArrayOf(9).toList(), app.rx.ReadValue(emptyMap()).toList())
  }
}
