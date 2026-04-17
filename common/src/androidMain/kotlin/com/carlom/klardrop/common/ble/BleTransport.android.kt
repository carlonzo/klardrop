package com.carlom.klardrop.common.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

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
      .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
      .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
      .setConnectable(true)
      .build()

    val serviceUuid = ParcelUuid(UUID.fromString(BleConstants.SERVICE_UUID))
    // Short device id (≤8 chars, ASCII) is carried as the service-data payload so peers
    // can recognise this peripheral without connecting. Kept short to fit the 31-byte
    // advertisement budget alongside the 128-bit service UUID.
    val data = AdvertiseData.Builder()
      .setIncludeDeviceName(false)
      .addServiceUuid(serviceUuid)
      .addServiceData(serviceUuid, currentDevice.shortDeviceId.encodeToByteArray())
      .build()

    val callback = object : AdvertiseCallback() {
      override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
        log(TAG, "BLE advertising started as ${currentDevice.shortDeviceId}")
      }

      override fun onStartFailure(errorCode: Int) {
        log(TAG, "BLE advertising failed with code $errorCode")
      }
    }
    advertiseCallback = callback
    advertiser.startAdvertising(settings, data, callback)
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
    val filter = ScanFilter.Builder().setServiceUuid(serviceUuid).build()
    val settings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .build()

    val callback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        val record = result.scanRecord ?: return
        val shortDeviceId = record.serviceData
          ?.get(serviceUuid)
          ?.decodeToString()
          ?: return
        trySend(
          BlePeerEvent.Found(
            address = result.device.address,
            shortDeviceId = shortDeviceId,
            localName = record.deviceName,
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

  private companion object {
    const val TAG = "BleTransport.android"
  }
}
