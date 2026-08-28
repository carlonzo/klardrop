package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.types.Variant

/**
 * Shared D-Bus system-bus connection to org.bluez plus the BLE capability probe.
 *
 * Everything here is unprivileged: BlueZ exposes GattManager1 (>= 5.42) and
 * LEAdvertisingManager1 (>= 5.48) on the system bus, and its D-Bus policy lets any
 * local user own a per-app GATT application/advertisement.
 */
object BlueZConnection {

  const val BLUEZ_SERVICE = "org.bluez"
  const val GATT_MANAGER = "org.bluez.GattManager1"
  const val LE_ADVERTISING_MANAGER = "org.bluez.LEAdvertisingManager1"

  @Volatile
  private var connection: DBusConnection? = null

  /**
   * Shared system-bus connection, or null when D-Bus is unreachable (no bluetoothd,
   * no socket, permission denied). Reconnects lazily after a dropped connection.
   */
  @Synchronized
  fun connection(): DBusConnection? {
    connection?.let { existing -> if (existing.isConnected) return existing }
    return try {
      DBusConnectionBuilder.forSystemBus().build().also { connection = it }
    } catch (e: Exception) {
      log(TAG, "D-Bus system bus unavailable", e)
      null
    }
  }

  /**
   * Probes BlueZ for adapters usable for both GATT client and peripheral roles.
   * Any failure (no bus, no bluetoothd, no capable adapter) yields supported=false.
   */
  suspend fun probe(): BlueZCapability = withContext(Dispatchers.IO) {
    val conn = connection() ?: return@withContext BlueZCapability(false, emptyList())
    try {
      val root = conn.getRemoteObject(BLUEZ_SERVICE, "/", ObjectManager::class.java)
      val adapters = capableAdapters(root.GetManagedObjects())
      BlueZCapability(supported = adapters.isNotEmpty(), adapterPaths = adapters.map { it.path })
    } catch (e: Exception) {
      log(TAG, "BlueZ capability probe failed", e)
      BlueZCapability(false, emptyList())
    }
  }

  /** Pure filter: object paths exposing BOTH GattManager1 and LEAdvertisingManager1. */
  fun capableAdapters(
    managedObjects: Map<DBusPath, Map<String, Map<String, Variant<*>>>>,
  ): List<DBusPath> =
    managedObjects.filterValues { interfaces ->
      GATT_MANAGER in interfaces && LE_ADVERTISING_MANAGER in interfaces
    }.keys.toList()

  private const val TAG = "BlueZConnection"
}
