package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.BleSession
import com.carlom.klardrop.common.discovery.CurrentDevice
import kotlinx.coroutines.flow.Flow
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.ObjectManager

/**
 * The Linux `BleTransport` contract, composed from the role classes over one
 * [BlueZFacade]: advertising ([LinuxBleAdvertiser]), central ([LinuxBleCentral]),
 * and peripheral ([LinuxBlePeripheral]) — both roles simultaneously, since BlueZ
 * hosts GATT server and client on the same adapter.
 *
 * `isSupported` is the BlueZ capability probe ([BlueZConnection.probe]): true when
 * the system bus is reachable and at least one adapter exposes both GattManager1
 * and LEAdvertisingManager1. Construction never touches D-Bus — the facade
 * connects lazily on the first role operation — so building this on hosts without
 * bluetoothd stays cheap and side-effect free.
 */
class LinuxBlueZTransport(private val facade: BlueZFacade) {

  /** Production constructor: both roles over one lazily-connected composite facade. */
  constructor() : this(BlueZCompositeFacade())

  private val advertiser = LinuxBleAdvertiser(facade)
  private val central = LinuxBleCentral(facade)
  private val peripheral = LinuxBlePeripheral(facade)

  suspend fun isSupported(): Boolean = facade.probeCapability().supported

  suspend fun startAdvertising(currentDevice: CurrentDevice) =
    advertiser.startAdvertising(currentDevice)

  suspend fun stopAdvertising() = advertiser.stopAdvertising()

  fun scanForPeers(): Flow<BlePeerEvent> = central.scanForPeers()

  suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession =
    central.connectCentral(address, remoteShortDeviceId)

  fun serveGatt(): Flow<BleSession> = peripheral.serveGatt()
}

/**
 * One [BlueZFacade] exposing both roles: peripheral ops delegate to
 * [BlueZPeripheralFacade], central ops to [BlueZCentralFacade] — both over the same
 * capable adapter, constructed on first use. The adapter path is resolved with the
 * same filter as the capability probe ([BlueZConnection.capableAdapters]).
 */
internal class BlueZCompositeFacade : BlueZFacade {

  private val peripheral: BlueZFacade by lazy { BlueZPeripheralFacade(connection(), adapterPath()) }
  private val central: BlueZFacade by lazy { BlueZCentralFacade(connection(), adapterPath()) }

  override suspend fun probeCapability(): BlueZCapability = BlueZConnection.probe()

  override suspend fun exportApplication() = peripheral.exportApplication()

  override suspend fun unregisterApplication() = peripheral.unregisterApplication()

  override suspend fun notifyValue(centralId: String, value: ByteArray) =
    peripheral.notifyValue(centralId, value)

  override fun onCharacteristicWrite(listener: ((String, ByteArray) -> Unit)?) =
    peripheral.onCharacteristicWrite(listener)

  override fun onCentralSubscription(listener: ((String, Boolean) -> Unit)?) =
    peripheral.onCentralSubscription(listener)

  override suspend fun startAdvertising(currentDevice: CurrentDevice) =
    peripheral.startAdvertising(currentDevice)

  override suspend fun stopAdvertising() = peripheral.stopAdvertising()

  override suspend fun startScan() = central.startScan()

  override suspend fun stopScan() = central.stopScan()

  override fun onPeerFound(listener: ((BlePeerEvent.Found) -> Unit)?) =
    central.onPeerFound(listener)

  override fun onPeerLost(listener: ((String) -> Unit)?) = central.onPeerLost(listener)

  override suspend fun connect(
    address: String,
    onNotify: (ByteArray) -> Unit,
    onDisconnected: () -> Unit,
  ): BlueZPeerLink = central.connect(address, onNotify, onDisconnected)

  private fun connection(): DBusConnection =
    checkNotNull(BlueZConnection.connection()) {
      "D-Bus system bus unavailable — BlueZ BLE requires org.bluez"
    }

  /** First adapter exposing both GattManager1 and LEAdvertisingManager1. */
  private fun adapterPath(): String {
    val root = connection().getRemoteObject(BlueZConnection.BLUEZ_SERVICE, "/", ObjectManager::class.java)
    return BlueZConnection.capableAdapters(root.GetManagedObjects()).firstOrNull()?.path
      ?: throw IllegalStateException("No BlueZ adapter with GattManager1 + LEAdvertisingManager1")
  }
}