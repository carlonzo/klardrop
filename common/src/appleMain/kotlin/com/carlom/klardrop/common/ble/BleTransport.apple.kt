package com.carlom.klardrop.common.ble

import com.carlom.klardrop.common.ble.apple.AppleBleSession
import com.carlom.klardrop.common.ble.apple.AppleL2capBleSession
import com.carlom.klardrop.common.ble.apple.toByteArray
import com.carlom.klardrop.common.ble.apple.toNSData
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import com.carlom.klardrop.common.utils.nonFatalCoroutineExceptionHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds
import platform.CoreBluetooth.CBATTErrorInvalidOffset
import platform.CoreBluetooth.CBATTErrorReadNotPermitted
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBL2CAPChannel
import platform.CoreBluetooth.CBL2CAPPSM
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
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
internal actual class BleTransport {

  private val cbQueue = dispatch_queue_create("com.carlom.klardrop.ble", null)
  // The handler is mandatory, not decorative: without it an uncaught throw from a chunk pump
  // reaches kotlinx.coroutines' final resort, which aborts the process on Kotlin/Native.
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default + nonFatalCoroutineExceptionHandler("BleTransport"),
  )

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
  @Volatile private var psm: Int? = null
  private var published = false
  private val centralL2capSessions = mutableMapOf<String, AppleL2capBleSession>()

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

  actual suspend fun startAdvertising(currentDevice: CurrentDevice, lowPower: Boolean) {
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

  actual fun scanForPeers(lowPower: Boolean): Flow<BlePeerEvent> = callbackFlow {
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
    try {
      return withTimeout(20.seconds) {
        peripheral.delegate = centralPeripheralDelegate
        centralManager.connectPeripheral(peripheral, options = null)
        pending.discovered.await()
        val session = connectPreferringL2cap(
          peerLabel = address,
          readPsm = {
            val psmChar = pending.psmChar
            if (psmChar == null) {
              null
            } else {
              peripheral.readValueForCharacteristic(psmChar)
              BlePsm.decode(pending.psmRead.await())
            }
          },
          openL2cap = { psm ->
            peripheral.openL2CAPChannel(psm.toUShort())
            val channel = try {
              pending.l2capOpened.await()
            } catch (e: CancellationException) {
              // No API to cancel an in-flight open: drop the channel if it arrives later.
              pending.l2capAbandoned = true
              // cancel() is a no-op if already completed (cancelled => getCompleted throws => null);
              // otherwise it makes a later complete() fail so the delegate drops the channel.
              pending.l2capOpened.cancel()
              runCatching { pending.l2capOpened.getCompleted() }.getOrNull()?.let { dropChannel(it) }
              throw e
            }
            lateinit var s: AppleL2capBleSession
            s = AppleL2capBleSession(
              channel = channel,
              deviceId = remoteShortDeviceId,
              onClose = {
                if (pending.adopted) centralManager.cancelPeripheralConnection(peripheral)
                // May run on cbQueue itself (didDisconnect): async, never sync.
                dispatch_async(cbQueue) {
                  if (centralL2capSessions[address] === s) centralL2capSessions.remove(address)
                }
              },
            )
            try {
              s.open()
            } catch (t: Throwable) {
              s.close()
              throw t
            }
            dispatch_sync(cbQueue) { centralL2capSessions[address] = s }
            s
          },
          openGatt = {
            val tx = pending.tx!!
            val rx = pending.rx!!
            val gatt = AppleBleSession(
              deviceId = remoteShortDeviceId,
              mtu = pending.mtu,
              sender = { bytes ->
                peripheral.writeValue(
                  data = bytes.toNSData(),
                  forCharacteristic = tx,
                  type = CBCharacteristicWriteWithResponse,
                )
              },
              closer = { centralManager.cancelPeripheralConnection(peripheral) },
            )
            centralSessions[address] = gatt
            peripheral.setNotifyValue(true, forCharacteristic = rx)
            pending.notifySubscribed.await()
            gatt
          },
        )
        if (session is AppleL2capBleSession) {
          pending.adopted = true
        } else {
          // GATT won: drop any stale L2CAP entry (coroutine thread, so go through cbQueue).
          dispatch_sync(cbQueue) { centralL2capSessions.remove(address) }
        }
        session
      }
    } catch (t: Throwable) {
      centralSessions.remove(address)
      var stale: AppleL2capBleSession? = null
      dispatch_sync(cbQueue) { stale = centralL2capSessions.remove(address) }
      stale?.close()
      centralManager.cancelPeripheralConnection(peripheral)
      throw t
    } finally {
      connectPending.remove(address)
    }
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
    val psmChar = CBMutableCharacteristic(
      type = CBUUID.UUIDWithString(BleConstants.PSM_CHARACTERISTIC_UUID),
      properties = CBCharacteristicPropertyRead,
      value = null, // non-null would be cached by CoreBluetooth; the PSM is only known later
      permissions = CBAttributePermissionsReadable,
    )
    service.setCharacteristics(listOf(tx, rx, psmChar))
    rxCharacteristic = rx
    if (peripheralManager.state == CBManagerStatePoweredOn) {
      peripheralManager.addService(service)
      serviceInstalled = true
      publishL2cap(peripheralManager)
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
      // Without the Klardrop service-data we can't know the peer's real shortDeviceId.
      // Falling back to peripheral.name (the system Bluetooth name like "Galaxy A32")
      // synthesised a bogus deviceId that wouldn't merge with the same peer's mDNS
      // entry — the user saw the Samsung listed twice on iPad: once under the marketing
      // name (bogus deviceId), once under the real mDNS deviceId. Drop the event; the
      // scan callback will fire again as the peer keeps advertising and the next packet
      // typically carries the service data we need.
      val shortId = decodeShortDeviceId(advertisementData)
      if (shortId == null) {
        return
      }
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
      connectPending[peerId]?.fail(IllegalStateException("connect failed: ${error?.localizedDescription}"))
    }

    @ObjCSignatureOverride
    override fun centralManager(
      central: CBCentralManager,
      didDisconnectPeripheral: CBPeripheral,
      error: NSError?,
    ) {
      val peerId = didDisconnectPeripheral.identifier.UUIDString
      connectPending[peerId]?.fail(IllegalStateException("disconnected during handshake: ${error?.localizedDescription}"))
      centralSessions.remove(peerId)?.markRemoteClosed()
      centralL2capSessions.remove(peerId)?.close()
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
          CBUUID.UUIDWithString(BleConstants.PSM_CHARACTERISTIC_UUID),
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
      val psmChar = chars.firstOrNull { (it as CBCharacteristic).UUID == CBUUID.UUIDWithString(BleConstants.PSM_CHARACTERISTIC_UUID) } as? CBCharacteristic
      val pending = connectPending[peripheral.identifier.UUIDString] ?: return
      pending.tx = tx
      pending.rx = rx
      pending.psmChar = psmChar
      pending.mtu = peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithResponse).toInt()
      pending.discovered.complete(Unit)
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didUpdateNotificationStateForCharacteristic: CBCharacteristic,
      error: NSError?,
    ) {
      val peerId = peripheral.identifier.UUIDString
      val pending = connectPending[peerId] ?: return
      if (error != null) {
        pending.notifySubscribed.completeExceptionally(
          IllegalStateException("notify subscription failed: ${error.localizedDescription}"),
        )
        return
      }
      pending.notifySubscribed.complete(Unit)
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didUpdateValueForCharacteristic: CBCharacteristic,
      error: NSError?,
    ) {
      val peerId = peripheral.identifier.UUIDString
      if (didUpdateValueForCharacteristic.UUID == CBUUID.UUIDWithString(BleConstants.PSM_CHARACTERISTIC_UUID)) {
        val bytes = if (error != null) null else didUpdateValueForCharacteristic.value?.toByteArray()
        connectPending[peerId]?.psmRead?.complete(bytes)
        return
      }
      if (didUpdateValueForCharacteristic.UUID != CBUUID.UUIDWithString(BleConstants.RX_CHARACTERISTIC_UUID)) return
      val session = centralSessions[peerId] ?: return
      val value = didUpdateValueForCharacteristic.value ?: return
      session.pushIncoming(value.toByteArray())
    }

    @ObjCSignatureOverride
    override fun peripheral(
      peripheral: CBPeripheral,
      didOpenL2CAPChannel: CBL2CAPChannel?,
      error: NSError?,
    ) {
      val pending = connectPending[peripheral.identifier.UUIDString]
      if (error != null || didOpenL2CAPChannel == null) {
        pending?.l2capOpened?.completeExceptionally(
          IllegalStateException("L2CAP open failed: ${error?.localizedDescription}"),
        )
        return
      }
      // Late arrival after cancellation: close the streams and drop the channel.
      if (pending == null || pending.l2capAbandoned || !pending.l2capOpened.complete(didOpenL2CAPChannel)) {
        dropChannel(didOpenL2CAPChannel)
      }
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
        publishL2cap(peripheral)
      } else {
        psm = null
        published = false
      }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
      peripheral: CBPeripheralManager,
      didPublishL2CAPChannel: CBL2CAPPSM,
      error: NSError?,
    ) {
      if (error != null) {
        log(TAG, "L2CAP publish failed: ${error.localizedDescription}")
        published = false // let a later PoweredOn retry
        return
      }
      psm = didPublishL2CAPChannel.toInt()
      log(TAG, "L2CAP listening psm=$psm")
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
      peripheral: CBPeripheralManager,
      didOpenL2CAPChannel: CBL2CAPChannel?,
      error: NSError?,
    ) {
      if (error != null || didOpenL2CAPChannel == null) {
        log(TAG, "L2CAP accept failed: ${error?.localizedDescription}")
        return
      }
      // No set of accepted sessions is kept: the transport lives for the app lifetime and each
      // session is closed by its bridge on EOF/error (deviation from phaseC.md step 5).
      val session = AppleL2capBleSession(
        channel = didOpenL2CAPChannel,
        deviceId = didOpenL2CAPChannel.peer?.identifier?.UUIDString ?: "unknown",
        onClose = {},
      )
      log(TAG, "L2CAP accepted channel from ${session.deviceId}")
      scope.launch {
        try {
          session.open()
        } catch (t: Throwable) {
          session.close()
          throw t
        }
        if (inboundSessions.trySend(session).isFailure) session.close()
      }
    }

    override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveReadRequest: CBATTRequest) {
      val req = didReceiveReadRequest
      val p = psm
      if (req.characteristic.UUID != CBUUID.UUIDWithString(BleConstants.PSM_CHARACTERISTIC_UUID) || p == null) {
        peripheral.respondToRequest(req, withResult = CBATTErrorReadNotPermitted)
        return
      }
      val bytes = BlePsm.encode(p)
      val offset = req.offset.toInt()
      if (offset > bytes.size) {
        peripheral.respondToRequest(req, withResult = CBATTErrorInvalidOffset)
        return
      }
      req.value = bytes.copyOfRange(offset, bytes.size).toNSData()
      peripheral.respondToRequest(req, withResult = CBATTErrorSuccess)
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

  private fun publishL2cap(manager: CBPeripheralManager) {
    if (published) return
    published = true
    manager.publishL2CAPChannelWithEncryption(false) // insecure like Android; UKEY2 encrypts on top
  }

  private fun dropChannel(ch: CBL2CAPChannel) {
    dispatch_async(dispatch_get_main_queue()) {
      ch.inputStream?.close()
      ch.outputStream?.close()
    }
  }

  private fun failConnect(peripheral: CBPeripheral, reason: String) {
    val peerId = peripheral.identifier.UUIDString
    connectPending[peerId]?.fail(IllegalStateException(reason))
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
  val discovered = CompletableDeferred<Unit>()
  val psmRead = CompletableDeferred<ByteArray?>()
  val l2capOpened = CompletableDeferred<CBL2CAPChannel>()
  val notifySubscribed = CompletableDeferred<Unit>()
  @Volatile var tx: CBCharacteristic? = null
  @Volatile var rx: CBCharacteristic? = null
  @Volatile var psmChar: CBCharacteristic? = null
  @Volatile var mtu: Int = 0
  @Volatile var l2capAbandoned = false
  /** Set once connectPreferringL2cap returned the L2CAP session; before that a losing L2CAP session must not drop the link. */
  @Volatile var adopted = false

  fun fail(t: Throwable) {
    discovered.completeExceptionally(t)
    psmRead.completeExceptionally(t)
    l2capOpened.completeExceptionally(t)
    notifySubscribed.completeExceptionally(t)
  }
}
