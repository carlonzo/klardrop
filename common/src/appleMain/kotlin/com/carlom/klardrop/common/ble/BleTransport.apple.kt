package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.ble.apple.AppleBleSession
import com.carlom.klardrop.common.ble.apple.toByteArray
import com.carlom.klardrop.common.ble.apple.toNSData
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile

/**
 * Apple (iOS) BLE transport using CoreBluetooth cinterop.
 *
 * Both central + peripheral roles are hosted by this single class. CoreBluetooth
 * callbacks dispatch on a dedicated serial queue; chunk routing through Kotlin
 * coroutines uses a class-scoped [CoroutineScope] so we don't need GlobalScope.
 *
 * GATT wire format matches Android + macOS-helper peers; framing happens in
 * [BleFraming] above the [BleSession] returned here.
 */
@OptIn(ExperimentalForeignApi::class)
actual class BleTransport {

  private val cbQueue = dispatch_queue_create("com.carlom.klardrop.ble", null)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  // null until the first peripheralManagerDidUpdateState / centralManagerDidUpdateState
  // callback fires. We use `filterNotNull().first()` to await readiness.
  private val centralState = MutableStateFlow<Long?>(null)
  private val peripheralState = MutableStateFlow<Long?>(null)

  private val centralDelegate = CentralDelegate()
  private val peripheralDelegate = PeripheralDelegate()
  private val centralPeripheralDelegate = CentralRolePeripheralDelegate()

  private val centralManager: CBCentralManager =
    CBCentralManager(centralDelegate, cbQueue, options = null)
  private val peripheralManager: CBPeripheralManager =
    CBPeripheralManager(peripheralDelegate, cbQueue, options = null)

  private val discovered = mutableMapOf<String, CBPeripheral>()
  private val centralSessions = mutableMapOf<String, AppleBleSession>()
  private val peripheralSessions = mutableMapOf<String, AppleBleSession>()
  private var rxCharacteristic: CBMutableCharacteristic? = null
  private var serviceInstalled = false

  /**
   * Short device id we want to be advertising, or null when advertising is off.
   *
   * CoreBluetooth only accepts `addService` / `startAdvertising` once the **peripheral**
   * manager reports `poweredOn`, and that arrives on its own delegate callback,
   * independently of the central manager's. [isSupported] — the gate the discovery layer
   * runs before calling [startAdvertising] — only awaits the *central* manager, so
   * [startAdvertising] is routinely reached while the peripheral manager is still
   * `.unknown`. Recording the intent here and re-applying it from
   * `peripheralManagerDidUpdateState` makes advertising survive both that startup race
   * and a later Bluetooth power cycle (CoreBluetooth silently drops published services
   * and stops advertising when the radio goes down, and never restores either).
   */
  @Volatile private var desiredAdvertiseShortId: String? = null

  private val inboundSessions = Channel<BleSession>(capacity = Channel.UNLIMITED)
  private val peerEvents = Channel<BlePeerEvent>(capacity = Channel.UNLIMITED)
  private val connectPending = mutableMapOf<String, ConnectPending>()

  // No service install here: a CBPeripheralManager's state is always `.unknown`
  // immediately after construction, so `addService` would be dropped. The service is
  // published from `peripheralManagerDidUpdateState` (and re-published after a power
  // cycle) instead.

  actual suspend fun isSupported(): Boolean {
    val current = centralState.filterNotNull().first()
    val ok = current == CBManagerStatePoweredOn
    if (!ok) log(TAG, "Apple BLE not powered on (state=$current)")
    return ok
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    desiredAdvertiseShortId = currentDevice.shortDeviceId
    applyAdvertisingState()
  }

  actual suspend fun stopAdvertising() {
    desiredAdvertiseShortId = null
    if (peripheralManager.isAdvertising) peripheralManager.stopAdvertising()
  }

  /**
   * Push [desiredAdvertiseShortId] into CoreBluetooth. Safe to call as often as we like:
   * it no-ops while the peripheral manager is down, and the poweredOn callback calls it
   * again once the manager comes up.
   */
  private fun applyAdvertisingState() {
    val shortId = desiredAdvertiseShortId ?: return
    if (peripheralManager.state != CBManagerStatePoweredOn) {
      log(TAG, "Peripheral manager not powered on (state=${peripheralManager.state}); advertising deferred")
      return
    }
    // Publish the GATT service before advertising: a peer that connects on the strength
    // of our advertisement must find the Klardrop service already there.
    installPeripheralService()

    // What we advertise is decided in commonMain; this only translates the records
    // CoreBluetooth is able to express. `startAdvertising` supports exactly two keys —
    // service UUIDs and local name — and silently drops everything else, so the
    // payload's service-data records are Android's to carry.
    val payload = klardropAdvertisePayload(shortId)
    val data = buildMap<Any?, Any?> {
      put(
        CBAdvertisementDataServiceUUIDsKey,
        payload.primary.serviceUuids.map { CBUUID.UUIDWithString(it) },
      )
      // Conditional because the dict bridges to an NSDictionary, which cannot hold a nil.
      payload.primary.localName?.let { put(CBAdvertisementDataLocalNameKey, it) }
    }
    if (peripheralManager.isAdvertising) peripheralManager.stopAdvertising()
    peripheralManager.startAdvertising(data)
    log(TAG, "BLE advertising as '${payload.primary.localName}'")
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> = callbackFlow {
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    if (centralManager.state == CBManagerStatePoweredOn) {
      centralManager.scanForPeripheralsWithServices(
        listOf(serviceUUID),
        // Duplicates ON, matching Android's CALLBACK_TYPE_ALL_MATCHES. With CoreBluetooth's
        // default de-duplication we get roughly one didDiscover per peer per scan, which
        // breaks two things: a peer whose first packet arrives without the scan-response
        // service-data is dropped by the decoder and never re-offered, and a peer that
        // stays put never refreshes its VisibleDevices lastSeenTimestamp, so the 5-minute
        // staleness sweep evicts a device that is sitting right there advertising.
        options = mapOf<Any?, Any?>(
          CBCentralManagerScanOptionAllowDuplicatesKey to true,
        ),
      )
    }
    val pumpJob = scope.launch {
      for (event in peerEvents) trySend(event)
    }
    awaitClose {
      pumpJob.cancel()
      if (centralManager.state == CBManagerStatePoweredOn) centralManager.stopScan()
    }
  }

  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    val peripheral = discovered[address]
      ?: throw IllegalStateException("Peer $address not in scan cache; scan first")
    val pending = ConnectPending(remoteShortDeviceId = remoteShortDeviceId)
    connectPending[address] = pending
    peripheral.delegate = centralPeripheralDelegate
    centralManager.connectPeripheral(peripheral, options = null)
    return pending.awaitSession()
  }

  actual fun serveGatt(): Flow<BleSession> = callbackFlow {
    val pumpJob = scope.launch {
      for (session in inboundSessions) trySend(session)
    }
    awaitClose { pumpJob.cancel() }
  }

  // ──────────────────────────────────────────────────────────────────────────

  /**
   * Publish the Klardrop GATT service. No-op until the peripheral manager is powered on
   * (CoreBluetooth ignores `addService` before that) and idempotent afterwards;
   * `peripheralManagerDidUpdateState` clears [serviceInstalled] on power-down so the
   * service is re-published on the next power-up.
   */
  private fun installPeripheralService() {
    if (serviceInstalled) return
    if (peripheralManager.state != CBManagerStatePoweredOn) return
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    val txUuid = CBUUID.UUIDWithString(BleConstants.TX_CHARACTERISTIC_UUID)
    val rxUuid = CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID)

    val tx = CBMutableCharacteristic(
      type = txUuid,
      properties = CBCharacteristicPropertyWrite,
      value = null,
      permissions = CBAttributePermissionsWriteable,
    )
    val rx = CBMutableCharacteristic(
      type = rxUuid,
      properties = CBCharacteristicPropertyNotify,
      value = null,
      permissions = CBAttributePermissionsReadable,
    )
    val service = CBMutableService(type = serviceUUID, primary = true)
    service.setCharacteristics(listOf(tx, rx))
    rxCharacteristic = rx
    peripheralManager.addService(service)
    serviceInstalled = true
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Delegates.
  // ──────────────────────────────────────────────────────────────────────────

  private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
      centralState.value = central.state
      log(TAG, "central manager state: ${central.state}")
    }

    override fun centralManager(
      central: CBCentralManager,
      didDiscoverPeripheral: CBPeripheral,
      advertisementData: Map<Any?, *>,
      RSSI: NSNumber,
    ) {
      val peerId = didDiscoverPeripheral.identifier.UUIDString
      discovered[peerId] = didDiscoverPeripheral
      // Identity comes from the advertisement alone, via the shared codec. Peers whose
      // packet carries no Klardrop id are dropped rather than falling back to
      // peripheral.name (the system Bluetooth name like "Galaxy A32"): that synthesised
      // a bogus deviceId that wouldn't merge with the same peer's mDNS entry — the user
      // saw the Samsung listed twice on iPad, once under the marketing name and once
      // under the real id. Scanning allows duplicates, so a peer whose first packet was
      // incomplete is re-offered on the next one.
      val advertisement = BleAdvertisementCodec.decode(
        serviceData = serviceDataBytes(advertisementData),
        localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String,
      ) ?: return
      peerEvents.trySend(
        BlePeerEvent.Found(
          address = peerId,
          shortDeviceId = advertisement.shortDeviceId,
          localName = advertisement.friendlyName,
          rssi = RSSI.intValue,
        )
      )
    }

    override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
      didConnectPeripheral.discoverServices(
        listOf(CBUUID.UUIDWithString(BleConstants.SERVICE_UUID))
      )
    }

    @ObjCSignatureOverride
    override fun centralManager(
      central: CBCentralManager,
      didFailToConnectPeripheral: CBPeripheral,
      error: NSError?,
    ) {
      val peerId = didFailToConnectPeripheral.identifier.UUIDString
      connectPending.remove(peerId)?.fail(IllegalStateException("connect failed: ${error?.localizedDescription}"))
    }

    @ObjCSignatureOverride
    override fun centralManager(
      central: CBCentralManager,
      didDisconnectPeripheral: CBPeripheral,
      error: NSError?,
    ) {
      val peerId = didDisconnectPeripheral.identifier.UUIDString
      connectPending.remove(peerId)?.fail(IllegalStateException("disconnected during handshake: ${error?.localizedDescription}"))
      centralSessions.remove(peerId)?.markRemoteClosed()
    }
  }

  // CBPeripheralDelegate is bound to a CBPeripheral; we share one instance across
  // every peripheral we connect to in the central role.
  private inner class CentralRolePeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
    override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
      val service = peripheral.services?.firstOrNull {
        (it as CBService).UUID == CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
      } as? CBService ?: run {
        failConnect(peripheral, "Klardrop service missing on peer")
        return
      }
      peripheral.discoverCharacteristics(
        listOf(
          CBUUID.UUIDWithString(BleConstants.TX_CHARACTERISTIC_UUID),
          CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID),
        ),
        forService = service,
      )
    }

    override fun peripheral(
      peripheral: CBPeripheral,
      didDiscoverCharacteristicsForService: CBService,
      error: NSError?,
    ) {
      val txUuid = CBUUID.UUIDWithString(BleConstants.TX_CHARACTERISTIC_UUID)
      val rxUuid = CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID)
      val chars = didDiscoverCharacteristicsForService.characteristics ?: emptyList<Any?>()
      val tx = chars.firstOrNull { (it as CBCharacteristic).UUID == txUuid } as? CBCharacteristic
      val rx = chars.firstOrNull { (it as CBCharacteristic).UUID == rxUuid } as? CBCharacteristic
      if (tx == null || rx == null) {
        failConnect(peripheral, "TX/RX characteristic missing")
        return
      }
      val peerId = peripheral.identifier.UUIDString
      val pending = connectPending[peerId] ?: return
      val mtu = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt()
      val session = AppleBleSession(
        deviceId = pending.remoteShortDeviceId,
        mtu = mtu,
        sender = { bytes ->
          peripheral.writeValue(
            data = bytes.toNSData(),
            forCharacteristic = tx,
            type = CBCharacteristicWriteWithResponse,
          )
        },
        closer = { centralManager.cancelPeripheralConnection(peripheral) },
      )
      pending.attachSession(session)
      centralSessions[peerId] = session
      peripheral.setNotifyValue(true, forCharacteristic = rx)
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didUpdateNotificationStateForCharacteristic: CBCharacteristic,
      error: NSError?,
    ) {
      val peerId = peripheral.identifier.UUIDString
      val pending = connectPending.remove(peerId) ?: return
      if (error != null) {
        pending.fail(IllegalStateException("notify subscription failed: ${error.localizedDescription}"))
        return
      }
      pending.complete()
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didUpdateValueForCharacteristic: CBCharacteristic,
      error: NSError?,
    ) {
      if (didUpdateValueForCharacteristic.UUID != CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID)) return
      val peerId = peripheral.identifier.UUIDString
      val session = centralSessions[peerId] ?: return
      val value = didUpdateValueForCharacteristic.value ?: return
      session.pushIncoming(value.toByteArray())
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didWriteValueForCharacteristic: CBCharacteristic,
      error: NSError?,
    ) {
      val peerId = peripheral.identifier.UUIDString
      val session = centralSessions[peerId] ?: return
      session.completeNextWriteAck(error == null)
    }
  }

  private inner class PeripheralDelegate : NSObject(), CBPeripheralManagerDelegateProtocol {
    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
      peripheralState.value = peripheral.state
      log(TAG, "peripheral manager state: ${peripheral.state}")
      if (peripheral.state == CBManagerStatePoweredOn) {
        // Covers both the startup race (isSupported() gates on the *central* manager,
        // so startAdvertising can land before we get here) and a Bluetooth power cycle,
        // after which CoreBluetooth has dropped our service and stopped advertising.
        installPeripheralService()
        applyAdvertisingState()
      } else {
        // The radio went down: published services and every subscribed central are gone.
        serviceInstalled = false
        rxCharacteristic = null
        peripheralSessions.values.forEach { it.markRemoteClosed() }
        peripheralSessions.clear()
      }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
      peripheral: CBPeripheralManager,
      central: CBCentral,
      didSubscribeToCharacteristic: CBCharacteristic,
    ) {
      if (didSubscribeToCharacteristic.UUID != CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID)) return
      val centralId = central.identifier.UUIDString
      if (peripheralSessions.containsKey(centralId)) return
      val rx = rxCharacteristic ?: return
      val mtu = central.maximumUpdateValueLength.toInt()
      val session = AppleBleSession(
        deviceId = centralId,
        mtu = mtu,
        sender = { bytes ->
          val ok = peripheralManager.updateValue(
            value = bytes.toNSData(),
            forCharacteristic = rx,
            onSubscribedCentrals = listOf(central),
          )
          if (!ok) throw IllegalStateException("notify queue full")
        },
        closer = { /* server can't unilaterally drop a central */ },
      )
      peripheralSessions[centralId] = session
      inboundSessions.trySend(session)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
      peripheral: CBPeripheralManager,
      central: CBCentral,
      didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
      val centralId = central.identifier.UUIDString
      peripheralSessions.remove(centralId)?.markRemoteClosed()
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
      for (anyReq in didReceiveWriteRequests) {
        val req = anyReq as? CBATTRequest ?: continue
        val centralId = req.central.identifier.UUIDString
        val value = req.value
        if (req.characteristic.UUID == CBUUID.UUIDWithString(BleConstants.TX_CHARACTERISTIC_UUID) && value != null) {
          peripheralSessions[centralId]?.pushIncoming(value.toByteArray())
        }
        peripheral.respondToRequest(req, withResult = CBATTErrorSuccess)
      }
    }

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
      peripheralSessions.values.forEach { it.retryPendingSends() }
    }
  }

  // ──────────────────────────────────────────────────────────────────────────

  private fun failConnect(peripheral: CBPeripheral, reason: String) {
    val peerId = peripheral.identifier.UUIDString
    connectPending.remove(peerId)?.fail(IllegalStateException(reason))
    centralManager.cancelPeripheralConnection(peripheral)
  }

  /** Lift the Klardrop service-data bytes out of a CoreBluetooth advertisement dict. */
  private fun serviceDataBytes(advertisementData: Map<Any?, *>): ByteArray? {
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    @Suppress("UNCHECKED_CAST")
    val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<CBUUID, NSData>
    return serviceData?.get(serviceUUID)?.toByteArray()
  }

  private companion object {
    const val TAG = "BleTransport.apple"
  }
}

private class ConnectPending(val remoteShortDeviceId: String) {
  private val deferred = CompletableDeferred<AppleBleSession>()
  private var attached: AppleBleSession? = null

  suspend fun awaitSession(): AppleBleSession = deferred.await()

  fun attachSession(session: AppleBleSession) {
    attached = session
  }

  fun complete() {
    val s = attached ?: run {
      deferred.completeExceptionally(IllegalStateException("connect completed without session"))
      return
    }
    deferred.complete(s)
  }

  fun fail(t: Throwable) {
    deferred.completeExceptionally(t)
  }
}
