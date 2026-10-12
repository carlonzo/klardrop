@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.ble.linux

import cnames.structs.sd_bus
import cnames.structs.sd_bus_message
import com.carlom.klardrop.common.ble.sdbus.sd_bus_call
import com.carlom.klardrop.common.ble.sdbus.sd_bus_error
import com.carlom.klardrop.common.ble.sdbus.sd_bus_error_free
import com.carlom.klardrop.common.ble.sdbus.sd_bus_flush_close_unref
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_at_end
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_enter_container
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_exit_container
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_new_method_call
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_read_basic
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_skip
import com.carlom.klardrop.common.ble.sdbus.sd_bus_message_unref
import com.carlom.klardrop.common.ble.sdbus.sd_bus_open_system
import com.carlom.klardrop.common.utils.log
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.strerror

/**
 * sd-bus system-bus connection to org.bluez plus the BLE capability probe.
 *
 * Native counterpart of the JVM [BlueZConnection] (dbus-java): same contract — enumerate
 * adapters via `ObjectManager.GetManagedObjects` and keep the ones exposing BOTH
 * `GattManager1` and `LEAdvertisingManager1` (see [capableAdapters]).
 *
 * sd-bus notes that shaped this file:
 * - Kotlin/Native cannot call C varargs, so `sd_bus_call_method` (varargs) is off the
 *   table. Method calls are built with [sd_bus_message_new_method_call] + sent with
 *   [sd_bus_call], and replies are walked with the non-varargs trio
 *   `enter_container` / `read_basic` / `skip` — that covers everything the spike needs.
 * - The probe opens the system bus per call and closes it after (no shared-connection
 *   lifecycle yet); see follow-up note on [probeCapability].
 */
object SdBusConnection {

  const val BLUEZ_SERVICE = "org.bluez"
  const val BLUEZ_ROOT = "/"
  const val OBJECT_MANAGER = "org.freedesktop.DBus.ObjectManager"
  const val GET_MANAGED_OBJECTS = "GetManagedObjects"
  const val GATT_MANAGER = "org.bluez.GattManager1"
  const val LE_ADVERTISING_MANAGER = "org.bluez.LEAdvertisingManager1"

  /** 5s method-call timeout, in microseconds (sd_bus_call takes usec). */
  private const val CALL_TIMEOUT_USEC = 5_000_000UL

  /** Mirrors dbus-java `BlueZCapability`. */
  data class Capability(val supported: Boolean, val adapterPaths: List<String>)

  /** One entry of the `a{oa{sa{sv}}}` GetManagedObjects reply. */
  data class ManagedObject(val path: String, val interfaces: Set<String>)

  class SdBusException(message: String) : Exception(message)

  /**
   * Probes BlueZ for adapters usable for both GATT client and peripheral roles.
   * Any failure (no bus, no bluetoothd, no capable adapter) yields supported=false.
   * Blocking: runs one synchronous `sd_bus_call`, so callers must stay off the UI thread.
   */
  fun probeCapability(): Capability = memScoped {
    val busVar = alloc<CPointerVar<sd_bus>>()
    val openRc = sd_bus_open_system(busVar.ptr)
    if (openRc < 0) {
      log(TAG, "sd_bus_open_system failed: ${errnoName(-openRc)}")
      return Capability(false, emptyList())
    }
    val bus = busVar.value ?: return Capability(false, emptyList())
    try {
      val adapters = capableAdapters(getManagedObjects(bus))
      Capability(adapters.isNotEmpty(), adapters.map { it.path })
    } catch (e: SdBusException) {
      log(TAG, "BlueZ capability probe failed: ${e.message}")
      Capability(false, emptyList())
    } finally {
      sd_bus_flush_close_unref(bus)
    }
  }

  /** Pure filter: object paths exposing BOTH GattManager1 and LEAdvertisingManager1. */
  fun capableAdapters(objects: List<ManagedObject>): List<ManagedObject> =
    objects.filter { GATT_MANAGER in it.interfaces && LE_ADVERTISING_MANAGER in it.interfaces }

  private fun MemScope.getManagedObjects(bus: CPointer<sd_bus>): List<ManagedObject> {
    val callVar = alloc<CPointerVar<sd_bus_message>>()
    checkRc(
      sd_bus_message_new_method_call(bus, callVar.ptr, BLUEZ_SERVICE, BLUEZ_ROOT, OBJECT_MANAGER, GET_MANAGED_OBJECTS),
      "new_method_call GetManagedObjects",
    )
    val call = callVar.value ?: throw SdBusException("null method call")
    try {
      val error = alloc<sd_bus_error>()
      val replyVar = alloc<CPointerVar<sd_bus_message>>()
      val callRc = sd_bus_call(bus, call, CALL_TIMEOUT_USEC, error.ptr, replyVar.ptr)
      if (callRc < 0) {
        val detail = listOfNotNull(error.name?.toKString(), error.message?.toKString())
          .joinToString(" ").ifEmpty { errnoName(-callRc) }
        sd_bus_error_free(error.ptr)
        throw SdBusException("GetManagedObjects failed: $detail")
      }
      sd_bus_error_free(error.ptr)
      val reply = replyVar.value ?: throw SdBusException("null reply")
      try {
        return readManagedObjects(reply)
      } finally {
        sd_bus_message_unref(reply)
      }
    } finally {
      sd_bus_message_unref(call)
    }
  }

  /** Walks the `a{oa{sa{sv}}}` reply; property maps are skipped, only interface names kept. */
  private fun MemScope.readManagedObjects(reply: CPointer<sd_bus_message>): List<ManagedObject> {
    val out = mutableListOf<ManagedObject>()
    checkRc(sd_bus_message_enter_container(reply, 'a'.code.toByte(), "{oa{sa{sv}}}"), "enter objects")
    while (!atEnd(reply)) {
      checkRc(sd_bus_message_enter_container(reply, 'e'.code.toByte(), "oa{sa{sv}}"), "enter object")
      val path = readBasicString(reply, 'o'.code.toByte())
      val interfaces = mutableSetOf<String>()
      checkRc(sd_bus_message_enter_container(reply, 'a'.code.toByte(), "{sa{sv}}"), "enter interfaces")
      while (!atEnd(reply)) {
        checkRc(sd_bus_message_enter_container(reply, 'e'.code.toByte(), "sa{sv}"), "enter interface")
        interfaces += readBasicString(reply, 's'.code.toByte())
        checkRc(sd_bus_message_skip(reply, "a{sv}"), "skip properties")
        checkRc(sd_bus_message_exit_container(reply), "exit interface")
      }
      checkRc(sd_bus_message_exit_container(reply), "exit interfaces")
      checkRc(sd_bus_message_exit_container(reply), "exit object")
      out += ManagedObject(path, interfaces)
    }
    checkRc(sd_bus_message_exit_container(reply), "exit objects")
    return out
  }

  private fun MemScope.readBasicString(message: CPointer<sd_bus_message>, type: Byte): String {
    val out = alloc<CPointerVar<ByteVar>>()
    checkRc(sd_bus_message_read_basic(message, type, out.ptr), "read string")
    return out.value?.toKString() ?: ""
  }

  private fun atEnd(message: CPointer<sd_bus_message>): Boolean {
    val rc = sd_bus_message_at_end(message, 0)
    if (rc < 0) throw SdBusException("at_end failed: ${errnoName(-rc)}")
    return rc > 0
  }

  private fun checkRc(rc: Int, what: String) {
    if (rc < 0) throw SdBusException("$what failed: ${errnoName(-rc)}")
  }

  private fun errnoName(errno: Int): String = strerror(errno)?.toKString() ?: "errno $errno"

  private const val TAG = "SdBusConnection"
}
