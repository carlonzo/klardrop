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
  private var pendingService: CBMutableService? = null
  private var serviceInstalled = false

  private val inboundSessions = Channel<BleSession>(capacity = Channel.UNLIMITED)
  private val peerEvents = Channel<BlePeerEvent>(capacity = Channel.UNLIMITED)
  private val connectPending = mutableMapOf<String, ConnectPending>()

  init {
    installPeripheralService()
  }

  actual suspend fun isSupported(): Boolean {
    val current = centralState.filterNotNull().first()
    val ok = current == CBManagerStatePoweredOn
    if (!ok) log(TAG, "Apple BLE not powered on (state=$current)")
    return ok
  }

  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    if (peripheralManager.state != CBManagerStatePoweredOn) {
      log(TAG, "Cannot start advertising: peripheral manager not powered on")
      return
    }
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    val data = mapOf<Any?, Any?>(
      CBAdvertisementDataServiceUUIDsKey to listOf(serviceUUID),
      // Apple does not allow custom service-data on advertisements; carry the
      // short id in the local name so peers see it from the scan callback alone.
      CBAdvertisementDataLocalNameKey to currentDevice.shortDeviceId,
    )
    if (peripheralManager.isAdvertising) peripheralManager.stopAdvertising()
    peripheralManager.startAdvertising(data)
  }

  actual suspend fun stopAdvertising() {
    if (peripheralManager.isAdvertising) peripheralManager.stopAdvertising()
  }

  actual fun scanForPeers(): Flow<BlePeerEvent> = callbackFlow {
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    if (centralManager.state == CBManagerStatePoweredOn) {
      centralManager.scanForPeripheralsWithServices(listOf(serviceUUID), options = null)
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

  private fun installPeripheralService() {
    if (serviceInstalled) return
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
    if (peripheralManager.state == CBManagerStatePoweredOn) {
      peripheralManager.addService(service)
      serviceInstalled = true
    } else {
      pendingService = service
    }
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
      val shortId = decodeShortDeviceId(advertisementData) ?: didDiscoverPeripheral.name ?: "unknown"
      peerEvents.trySend(
        BlePeerEvent.Found(
          address = peerId,
          shortDeviceId = shortId,
          localName = didDiscoverPeripheral.name,
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
        pendingService?.let {
          peripheral.addService(it)
          serviceInstalled = true
          pendingService = null
        }
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

  private fun decodeShortDeviceId(advertisementData: Map<Any?, *>): String? {
    val serviceUUID = CBUUID.UUIDWithString(BleConstants.SERVICE_UUID)
    @Suppress("UNCHECKED_CAST")
    val serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? Map<CBUUID, NSData>
    val bytes = serviceData?.get(serviceUUID)
    if (bytes != null) return bytes.toByteArray().decodeToString()
    return advertisementData[CBAdvertisementDataLocalNameKey] as? String
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
