package com.carlom.klardrop.common.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Android BLE transport using [BluetoothLeAdvertiser] for advertising and
 * [android.bluetooth.le.BluetoothLeScanner] for peer discovery.
 *
 * Responsibility is intentionally narrow: advertise our service UUID + short device id,
 * and report discovered peers to callers. Session-level I/O (GATT connect/read/write) is
 * planned as a follow-up that reuses the existing Klardrop message serializer.
 */
actual class BleTransport(private val context: Context) {

  private val manager: BluetoothManager? =
    ContextCompat.getSystemService(context, BluetoothManager::class.java)
  private val adapter: BluetoothAdapter? = manager?.adapter

  private var advertiseCallback: AdvertiseCallback? = null

  actual suspend fun isSupported(): Boolean {
    val adapter = this.adapter ?: return false
    if (!adapter.isEnabled) return false
    if (!hasRuntimePermissions()) return false
    // Advertising is optional hardware — scanning still works without it, but for a
    // symmetric peer-to-peer flow we need both.
    return adapter.bluetoothLeAdvertiser != null
  }

  @SuppressLint("MissingPermission")
  actual suspend fun startAdvertising(currentDevice: CurrentDevice) {
    val adapter = this.adapter ?: run {
      log(TAG, "Bluetooth adapter unavailable; cannot advertise")
      return
    }
    if (!hasRuntimePermissions()) {
      log(TAG, "Missing BLUETOOTH_ADVERTISE permission; cannot advertise")
      return
    }
    val advertiser: BluetoothLeAdvertiser = adapter.bluetoothLeAdvertiser ?: run {
      log(TAG, "BLE advertising not supported on this device")
      return
    }

    // Replace any prior advertisement.
    advertiseCallback?.let { runCatching { advertiser.stopAdvertising(it) } }

    val settings = AdvertiseSettings.Builder()
      // LOW_LATENCY = ~100ms advertise interval (vs BALANCED's ~250ms),
      // HIGH tx power maximises range. Matches the LOW_LATENCY scan mode so
      // Android-Android peers see each other reliably without needing to be
      // within a few centimeters of one another.
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
      .setConnectable(true)
      .build()

    // The AD layout decision (which records go in primary vs scan response, and
    // which AD type carries the service UUID) is centralised in
    // `klardropAdvertisePayload` and unit-tested in `BleAdvertisePayloadTest`.
    val payload = klardropAdvertisePayload(currentDevice.shortDeviceId)
    val data = payload.primary.toAndroid()
    val scanResponse = payload.scanResponse?.toAndroid()

    val callback = object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        log(TAG, "BLE advertising started as ${currentDevice.shortDeviceId}")
      }

      override fun onStartFailure(errorCode: Int) {
        log(TAG, "BLE advertising failed with code $errorCode")
      }
    }
    advertiseCallback = callback
    if (scanResponse != null) {
      advertiser.startAdvertising(settings, data, scanResponse, callback)
    } else {
      advertiser.startAdvertising(settings, data, callback)
    }
  }

  @SuppressLint("MissingPermission")
  actual suspend fun stopAdvertising() {
    val advertiser = adapter?.bluetoothLeAdvertiser ?: return
    val callback = advertiseCallback ?: return
    runCatching { advertiser.stopAdvertising(callback) }
    advertiseCallback = null
  }

  @SuppressLint("MissingPermission")
  actual fun scanForPeers(): Flow<BlePeerEvent> = callbackFlow {
    val adapter = this@BleTransport.adapter
    val scanner = adapter?.bluetoothLeScanner
    if (adapter == null || scanner == null || !hasRuntimePermissions()) {
      log(TAG, "BLE scanning unavailable (adapter=$adapter, permissions=${hasRuntimePermissions()})")
      close()
      return@callbackFlow
    }

    val serviceUuid = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))
    // Match by service UUID alone. Apple peers (iOS + macOS helper) cannot include
    // custom service-data in their advertisements, so a service-data filter would
    // exclude them. We extract the short device id from service-data when present
    // (Android ↔ Android) or fall back to the local name (Android ↔ Apple).
    val filter = ScanFilter.Builder()
      .setServiceUuid(serviceUuid)
      .build()
    val settings = ScanSettings.Builder()
      // LOW_LATENCY = ~100% duty-cycle scanning. BALANCED gives ~50% windows that
      // commonly miss the other peer's advertisement bursts when both Android
      // peers are simultaneously advertising + scanning (half-duplex radio
      // schedules don't align). Trades battery for reliable peer-to-peer Android
      // BLE discovery, which is the primary fallback transport for Klardrop.
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .build()

    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        val record = result.scanRecord ?: return
        // Both peer types may encode `<shortId>|<friendlyName>` when possible:
        // Android peers carry the bare shortId in service-data (no friendly name
        // — the AD packet is full); Apple peers carry the combined string in the
        // local name (CB auto-spills to scan response when long).
        val rawServiceData = record.serviceData?.get(serviceUuid)?.decodeToString()
        val rawName = record.deviceName
        val combined = listOfNotNull(rawServiceData, rawName).firstOrNull { it.contains('|') }
        val shortDeviceId: String
        val friendlyName: String?
        if (combined != null) {
          val parts = combined.split('|', limit = 2)
          shortDeviceId = parts[0]
          friendlyName = parts.getOrNull(1)
        } else {
          // No combined payload — use whichever bare identifier is present. We do
          // NOT fall back to the BT MAC here because that would surface the same
          // peer twice (once with MAC, once with shortId once a scan response with
          // the local name arrives).
          shortDeviceId = rawServiceData ?: rawName ?: return
          friendlyName = rawName
        }
        trySend(
          BlePeerEvent.Found(
            address = result.device.address,
            shortDeviceId = shortDeviceId,
            localName = friendlyName,
            rssi = result.rssi,
          )
        )
      }

      override fun onScanFailed(errorCode: Int) {
        log(TAG, "BLE scan failed with code $errorCode")
        close()
      }
    }
    scanner.startScan(listOf(filter), settings, callback)
    awaitClose {
      runCatching { scanner.stopScan(callback) }
    }
  }

  private fun hasRuntimePermissions(): Boolean {
    // From API 31 the BLUETOOTH_* runtime perms are required; below that, the manifest
    // BLUETOOTH/BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION (install-time + runtime) cover it.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
        hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) &&
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
      hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  private fun hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

  // ──────────────────────────────────────────────────────────────────────────────────────
  // GATT Central (client) — connect to a discovered peer and expose a BleSession.
  // ──────────────────────────────────────────────────────────────────────────────────────

  @SuppressLint("MissingPermission")
  actual suspend fun connectCentral(address: String, remoteShortDeviceId: String): BleSession {
    val adapter = this.adapter
      ?: throw IllegalStateException("Bluetooth adapter unavailable")
    check(hasRuntimePermissions()) { "Missing BLUETOOTH_CONNECT permission" }

    val device: BluetoothDevice = adapter.getRemoteDevice(address)
    val serviceUuid = UUID.fromString(BleConstants.SERVICE_UUID)
    val txUuid = UUID.fromString(BleConstants.TX_CHARACTERISTIC_UUID)
    val rxUuid = UUID.fromString(BleConstants.RX_CHARACTERISTIC_UUID)
    val cccdUuid = UUID.fromString(BleConstants.CCCD_UUID)

    val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    // Step-by-step GATT setup: connect → onConnected → requestMtu → onMtuChanged →
    // discoverServices → onServicesDiscovered → setCharacteristicNotification +
    // write CCCD → onDescriptorWrite → session ready.
    val holder = CentralSessionHolder(
      remoteShortDeviceId = remoteShortDeviceId,
      incoming = incoming,
    )

    val callback = object : BluetoothGattCallback() {
      override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
          holder.fail(IllegalStateException("GATT connection failed status=$status"))
          runCatching { gatt.close() }
          incoming.close()
          return
        }
        when (newState) {
          BluetoothProfile.STATE_CONNECTED -> {
            log(TAG, "Central connected to $address, requesting MTU ${BleConstants.REQUESTED_MTU}")
            gatt.requestMtu(BleConstants.REQUESTED_MTU)
          }
          BluetoothProfile.STATE_DISCONNECTED -> {
            log(TAG, "Central disconnected from $address")
            holder.markClosed()
            runCatching { gatt.close() }
            incoming.close()
          }
        }
      }

      override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        holder.negotiatedMtu = (mtu - BleConstants.ATT_HEADER_SIZE).coerceAtLeast(20)
        log(TAG, "MTU changed to $mtu (payload=${holder.negotiatedMtu}) status=$status")
        gatt.discoverServices()
      }

      override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
          holder.fail(IllegalStateException("Service discovery failed status=$status"))
          return
        }
        val service = gatt.getService(serviceUuid)
        if (service == null) {
          holder.fail(IllegalStateException("Peer does not expose Klardrop BLE service"))
          return
        }
        val tx = service.getCharacteristic(txUuid)
        val rx = service.getCharacteristic(rxUuid)
        if (tx == null || rx == null) {
          holder.fail(IllegalStateException("Peer service missing TX/RX characteristics"))
          return
        }
        holder.txCharacteristic = tx
        gatt.setCharacteristicNotification(rx, true)
        val descriptor = rx.getDescriptor(cccdUuid)
        if (descriptor == null) {
          holder.fail(IllegalStateException("RX characteristic missing CCCD"))
          return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
      }

      override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (descriptor.uuid != cccdUuid) return
        if (status == BluetoothGatt.GATT_SUCCESS) {
          holder.complete(gatt)
        } else {
          holder.fail(IllegalStateException("CCCD write failed status=$status"))
        }
      }

      override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
      ) {
        if (characteristic.uuid != rxUuid) return
        @Suppress("DEPRECATION")
        val value = characteristic.value ?: return
        if (value.isEmpty()) return
        incoming.trySendBlocking(value.copyOf())
      }

      override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
      ) {
        if (characteristic.uuid != txUuid) return
        holder.onWriteAck(status == BluetoothGatt.GATT_SUCCESS)
      }
    }

    val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
      ?: throw IllegalStateException("connectGatt returned null for $address")

    return try {
      withTimeout(CONNECT_TIMEOUT) { holder.awaitReady() }
      AndroidCentralBleSession(
        gatt = gatt,
        txCharacteristic = holder.txCharacteristic!!,
        deviceId = remoteShortDeviceId,
        mtu = holder.negotiatedMtu,
        incoming = incoming,
        writeAckWaiter = holder::awaitNextWriteAck,
      )
    } catch (t: Throwable) {
      runCatching { gatt.close() }
      incoming.close()
      throw t
    }
  }

  // ──────────────────────────────────────────────────────────────────────────────────────
  // GATT Peripheral (server) — host the Klardrop service and emit a session per client.
  // ──────────────────────────────────────────────────────────────────────────────────────

  @SuppressLint("MissingPermission")
  actual fun serveGatt(): Flow<BleSession> = callbackFlow {
    val adapter = this@BleTransport.adapter
    val manager = this@BleTransport.manager
    if (adapter == null || manager == null || !hasRuntimePermissions()) {
      log(TAG, "Cannot start GATT server (adapter=$adapter permissions=${hasRuntimePermissions()})")
      close()
      return@callbackFlow
    }

    val serviceUuid = UUID.fromString(BleConstants.SERVICE_UUID)
    val txUuid = UUID.fromString(BleConstants.TX_CHARACTERISTIC_UUID)
    val rxUuid = UUID.fromString(BleConstants.RX_CHARACTERISTIC_UUID)
    val cccdUuid = UUID.fromString(BleConstants.CCCD_UUID)

    val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
      addCharacteristic(
        BluetoothGattCharacteristic(
          txUuid,
          BluetoothGattCharacteristic.PROPERTY_WRITE,
          BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
      )
      val rx = BluetoothGattCharacteristic(
        rxUuid,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
      )
      rx.addDescriptor(
        BluetoothGattDescriptor(
          cccdUuid,
          BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
      )
      addCharacteristic(rx)
    }

    // Per-central session state. MTU and subscription readiness are tracked here.
    val sessions = ConcurrentHashMap<String, PeripheralSessionBuilder>()
    // Keep a handle on the server so we can push notifications from the session.
    var serverRef: BluetoothGattServer? = null

    val serverCallback = object : BluetoothGattServerCallback() {
      override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        val key = device.address
        when (newState) {
          BluetoothProfile.STATE_CONNECTED -> {
            log(TAG, "Central ${device.address} connected to our GATT server")
            sessions[key] = PeripheralSessionBuilder(device)
          }
          BluetoothProfile.STATE_DISCONNECTED -> {
            log(TAG, "Central ${device.address} disconnected from our GATT server")
            sessions.remove(key)?.session?.markClosed()
          }
        }
      }

      override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
        sessions[device.address]?.negotiatedMtu =
          (mtu - BleConstants.ATT_HEADER_SIZE).coerceAtLeast(20)
        log(TAG, "GATT server MTU with ${device.address} = $mtu")
      }

      override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
      ) {
        val server = serverRef
        if (characteristic.uuid == txUuid && value != null) {
          sessions[device.address]?.pushIncoming(value.copyOf())
        }
        if (responseNeeded) {
          server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
      }

      override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
      ) {
        val server = serverRef
        if (descriptor.uuid == cccdUuid && value != null) {
          val enabled = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
          if (responseNeeded) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
          }
          if (enabled) {
            val builder = sessions[device.address] ?: return
            val rx = service.getCharacteristic(rxUuid)
            val session = AndroidPeripheralBleSession(
              server = server!!,
              device = device,
              rxCharacteristic = rx,
              deviceId = device.address,
              mtu = builder.negotiatedMtu,
            )
            builder.session = session
            // New subscriber → emit the session to the flow.
            trySend(session)
          }
        } else if (responseNeeded) {
          server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
        }
      }

      override fun onNotificationSent(device: BluetoothDevice, status: Int) {
        sessions[device.address]?.session?.onNotificationSent(status == BluetoothGatt.GATT_SUCCESS)
      }
    }

    val server = manager.openGattServer(context, serverCallback) ?: run {
      log(TAG, "openGattServer returned null; cannot host Klardrop service")
      close()
      return@callbackFlow
    }
    server.addService(service)
    serverRef = server

    awaitClose {
      runCatching { server.clearServices() }
      runCatching { server.close() }
      sessions.values.forEach { it.session?.markClosed() }
    }
  }

  private companion object {
    const val TAG = "BleTransport.android"
  }
}

private val CONNECT_TIMEOUT = 15.seconds
private val WRITE_ACK_TIMEOUT = 5.seconds

// ──────────────────────────────────────────────────────────────────────────────────────
// Helpers: central + peripheral session holders and BleSession implementations.
// ──────────────────────────────────────────────────────────────────────────────────────

/**
 * Tracks the multi-step GATT handshake (connect → MTU → discover → CCCD) so the
 * `connectCentral` caller can await a fully-ready session with a single suspend point.
 */
private class CentralSessionHolder(
  @Suppress("unused") val remoteShortDeviceId: String,
  val incoming: Channel<ByteArray>,
) {
  @Volatile var negotiatedMtu: Int = 20 // safe default before onMtuChanged fires
  @Volatile var txCharacteristic: BluetoothGattCharacteristic? = null
  private var readyCont: CancellableContinuation<Unit>? = null
  private val writeAckQueue = Channel<Boolean>(capacity = Channel.UNLIMITED)
  private var closed = false

  suspend fun awaitReady() = suspendCancellableCoroutine<Unit> { cont ->
    readyCont = cont
  }

  fun complete(@Suppress("UNUSED_PARAMETER") gatt: BluetoothGatt) {
    readyCont?.takeIf { it.isActive }?.resume(Unit)
    readyCont = null
  }

  fun fail(cause: Throwable) {
    readyCont?.takeIf { it.isActive }?.resumeWithException(cause)
    readyCont = null
  }

  fun markClosed() {
    closed = true
    readyCont?.takeIf { it.isActive }?.resumeWithException(IllegalStateException("Disconnected during handshake"))
    readyCont = null
    writeAckQueue.close()
  }

  fun onWriteAck(success: Boolean) {
    writeAckQueue.trySend(success)
  }

  suspend fun awaitNextWriteAck(): Boolean = writeAckQueue.receive()
}

@SuppressLint("MissingPermission")
private class AndroidCentralBleSession(
  private val gatt: BluetoothGatt,
  private val txCharacteristic: BluetoothGattCharacteristic,
  override val deviceId: String,
  override val mtu: Int,
  private val incoming: Channel<ByteArray>,
  private val writeAckWaiter: suspend () -> Boolean,
) : BleSession {

  private val writeLock = Mutex()
  @Volatile private var open = true
  override val isOpen: Boolean get() = open

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "BLE central session closed" }
    writeLock.withLock {
      val ok: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(txCharacteristic, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
      } else {
        @Suppress("DEPRECATION")
        run {
          txCharacteristic.value = chunk
          txCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
          if (gatt.writeCharacteristic(txCharacteristic)) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
        }
      }
      if (ok != BluetoothGatt.GATT_SUCCESS) {
        throw IllegalStateException("writeCharacteristic returned $ok")
      }
      val ack = withTimeout(WRITE_ACK_TIMEOUT) { writeAckWaiter() }
      if (!ack) throw IllegalStateException("TX write failed")
    }
  }

  override suspend fun receiveChunk(): ByteArray? =
    incoming.receiveCatching().getOrNull()

  override fun close() {
    if (!open) return
    open = false
    runCatching { gatt.disconnect() }
    runCatching { gatt.close() }
    incoming.close()
  }
}

/**
 * Mutable per-central bookkeeping on the peripheral side. Collected into an
 * [AndroidPeripheralBleSession] once the central subscribes to the RX characteristic.
 */
private class PeripheralSessionBuilder(@Suppress("unused") val device: BluetoothDevice) {
  @Volatile var negotiatedMtu: Int = 20
  @Volatile var session: AndroidPeripheralBleSession? = null

  fun pushIncoming(bytes: ByteArray) {
    session?.onIncomingWrite(bytes)
  }
}

@SuppressLint("MissingPermission")
private class AndroidPeripheralBleSession(
  private val server: BluetoothGattServer,
  private val device: BluetoothDevice,
  private val rxCharacteristic: BluetoothGattCharacteristic,
  override val deviceId: String,
  override val mtu: Int,
) : BleSession {

  private val incoming = Channel<ByteArray>(capacity = Channel.UNLIMITED)
  private val notificationAcks = Channel<Boolean>(capacity = Channel.UNLIMITED)
  private val writeLock = Mutex()
  @Volatile private var open = true
  override val isOpen: Boolean get() = open

  fun onIncomingWrite(chunk: ByteArray) {
    if (!open) return
    incoming.trySend(chunk.copyOf())
  }

  fun onNotificationSent(success: Boolean) {
    notificationAcks.trySend(success)
  }

  fun markClosed() {
    if (!open) return
    open = false
    incoming.close()
    notificationAcks.close()
  }

  override suspend fun sendChunk(chunk: ByteArray) {
    require(chunk.size <= mtu) { "chunk size ${chunk.size} exceeds mtu $mtu" }
    check(open) { "BLE peripheral session closed" }
    writeLock.withLock {
      val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        server.notifyCharacteristicChanged(device, rxCharacteristic, false, chunk) == BluetoothGatt.GATT_SUCCESS
      } else {
        @Suppress("DEPRECATION")
        run {
          rxCharacteristic.value = chunk
          server.notifyCharacteristicChanged(device, rxCharacteristic, false)
        }
      }
      if (!ok) throw IllegalStateException("notifyCharacteristicChanged returned false")
      val ack = withTimeout(WRITE_ACK_TIMEOUT) { notificationAcks.receive() }
      if (!ack) throw IllegalStateException("Notification delivery failed")
    }
  }

  override suspend fun receiveChunk(): ByteArray? =
    incoming.receiveCatching().getOrNull()

  override fun close() {
    if (!open) return
    open = false
    runCatching { server.cancelConnection(device) }
    incoming.close()
    notificationAcks.close()
  }
}


private fun BleAdvertisePayload.AdRecords.toAndroid(): AdvertiseData {
  val builder = AdvertiseData.Builder().setIncludeDeviceName(false)
  serviceUuids.forEach { builder.addServiceUuid(ParcelUuid(UUID.fromString(it))) }
  serviceData.forEach { (uuid, bytes) ->
    builder.addServiceData(ParcelUuid(UUID.fromString(uuid)), bytes)
  }
  return builder.build()
}
