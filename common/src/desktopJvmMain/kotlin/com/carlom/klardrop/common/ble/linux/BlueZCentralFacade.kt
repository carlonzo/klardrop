package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.ble.BlePeerEvent
import com.carlom.klardrop.common.ble.MAX_SHORT_DEVICE_ID_LEN
import com.carlom.klardrop.common.utils.log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRule
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.types.Variant

/**
 * Real [BlueZFacade] central-role implementation over a live D-Bus connection.
 *
 * Scan: `Adapter1.SetDiscoveryFilter` on the Klardrop service UUID + `StartDiscovery`.
 * Device1 objects arrive via ObjectManager `InterfacesAdded` (initial properties) and —
 * because BlueZ merges scan-response ServiceData into an already-known Device1 only
 * AFTER the object exists — via `PropertiesChanged` on the device path. Listening to
 * InterfacesAdded alone would miss most real peers. `InterfacesRemoved` → Lost.
 *
 * Connect: `Device1.Connect` → wait for `ServicesResolved` → resolve the Klardrop
 * service's TX/RX characteristics from GetManagedObjects → read `GattCharacteristic1.MTU`
 * (BlueZ ≥ 5.63; absent on older distros → conservative DEFAULT_MTU) → `StartNotify` on RX.
 * Remote disconnects arrive as Device1 `Connected=false` PropertiesChanged; the connect's
 * signal handlers remove themselves at that point.
 *
 * Every signal subscription goes through [DBusMatchRuleBuilder] rather than dbus-java's
 * `addSigHandler(Class, String, handler)` convenience overload: that String is the
 * *sender's unique bus name* (validated against `^:[0-9]*\.[0-9]*$`), not an object path,
 * and a well-known name like "org.bluez" never matches either — incoming signals carry
 * the sender's unique name, so dbus-java's client-side rule check would drop every one.
 */
class BlueZCentralFacade(
  private val connection: DBusConnection,
  private val adapterPath: String,
) : BlueZFacade {

  @Volatile private var foundListener: ((BlePeerEvent.Found) -> Unit)? = null
  @Volatile private var lostListener: ((String) -> Unit)? = null

  /** Device paths this facade reported Found for, mapped to their MAC address (for Lost). */
  private val knownDevices = ConcurrentHashMap<String, String>()

  @Volatile private var scanHandlers: List<AutoCloseable> = emptyList()

  override suspend fun probeCapability(): BlueZCapability = BlueZConnection.probe()

  override fun onPeerFound(listener: ((BlePeerEvent.Found) -> Unit)?) {
    foundListener = listener
  }

  override fun onPeerLost(listener: ((String) -> Unit)?) {
    lostListener = listener
  }

  override suspend fun startScan() = withContext(Dispatchers.IO) {
    check(scanHandlers.isEmpty()) { "BLE scan already running" }
    val handlers = listOf(
      connection.addSigHandler(
        interfacesAddedRule(),
        DBusSigHandler<ObjectManager.InterfacesAdded> { onInterfacesAdded(it) },
      ),
      connection.addSigHandler(
        interfacesRemovedRule(),
        DBusSigHandler<ObjectManager.InterfacesRemoved> { onInterfacesRemoved(it) },
      ),
      connection.addSigHandler(
        bluezPropertiesChangedRule(),
        DBusSigHandler<Properties.PropertiesChanged> { onDevicePropertiesChanged(it) },
      ),
    )
    try {
      val adapter = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, adapterPath, Adapter1::class.java)
      adapter.SetDiscoveryFilter(mapOf("UUIDs" to Variant(listOf(BleConstants.SERVICE_UUID), "as")))
      adapter.StartDiscovery()
      scanHandlers = handlers
    } catch (e: Exception) {
      handlers.forEach { runCatching { it.close() } }
      throw e
    }
  }

  override suspend fun stopScan() = withContext(Dispatchers.IO) {
    val handlers = scanHandlers
    scanHandlers = emptyList()
    handlers.forEach { runCatching { it.close() } }
    runCatching {
      connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, adapterPath, Adapter1::class.java).StopDiscovery()
    }.onFailure { log(TAG, "StopDiscovery failed", it) }
    knownDevices.clear()
  }

  override suspend fun connect(
    address: String,
    onNotify: (ByteArray) -> Unit,
    onDisconnected: () -> Unit,
  ): BlueZPeerLink = withContext(Dispatchers.IO) {
    val devicePath = resolveDevicePath(address)
    val device = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, devicePath, Device1::class.java)
    try {
      device.Connect()
    } catch (e: Exception) {
      throw IllegalStateException("BLE connect to $address failed", e)
    }
    if (!currentCoroutineContext().isActive) {
      // The 10s connect timeout (or the caller) gave up while we were blocked in Connect().
      runCatching { device.Disconnect() }
      throw CancellationException("BLE connect to $address cancelled")
    }
    awaitServicesResolved(devicePath, address)
    val (txPath, rxPath) = resolveCharacteristics(devicePath, address)
    val mtu = readMtu(rxPath)
    val rx = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, rxPath, GattCharacteristic1::class.java)
    val tx = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, txPath, GattCharacteristic1::class.java)

    val notifyHandler = connection.addSigHandler(
      propertiesChangedRule(rxPath),
      DBusSigHandler<Properties.PropertiesChanged> { sig ->
        (sig.propertiesChanged["Value"]?.value as? ByteArray)?.let(onNotify)
      },
    )
    // Self-cleaning on remote disconnect: Connected=false removes both handlers.
    var disconnectHandler: AutoCloseable? = null
    disconnectHandler = connection.addSigHandler(
      propertiesChangedRule(devicePath),
      DBusSigHandler<Properties.PropertiesChanged> { sig ->
        if (sig.propertiesChanged["Connected"]?.value == false) {
          runCatching { notifyHandler.close() }
          runCatching { disconnectHandler?.close() }
          onDisconnected()
        }
      },
    )
    try {
      rx.StartNotify()
    } catch (e: Exception) {
      runCatching { notifyHandler.close() }
      runCatching { disconnectHandler?.close() }
      throw IllegalStateException("StartNotify failed on $rxPath", e)
    }
    BlueZPeerLink(mtu = mtu, writeTx = { value ->
      withContext(Dispatchers.IO) { tx.WriteValue(value, emptyMap()) }
    })
  }

  // ── Scan signal plumbing ──────────────────────────────────────────────────

  private fun onInterfacesAdded(sig: ObjectManager.InterfacesAdded) {
    val device = sig.interfaces[DEVICE1] ?: return
    emitFoundIfKlardrop(sig.changedPath, device)
  }

  private fun onInterfacesRemoved(sig: ObjectManager.InterfacesRemoved) {
    if (DEVICE1 !in sig.interfaces) return
    val address = knownDevices.remove(sig.changedPath) ?: return
    lostListener?.invoke(address)
  }

  private fun onDevicePropertiesChanged(sig: Properties.PropertiesChanged) {
    if (sig.interfaceName != DEVICE1) return
    val devicePath = sig.path ?: return
    if (sig.propertiesChanged.containsKey("ServiceData")) {
      // Late scan-response merge: re-read the full property set so Found carries Address/RSSI.
      val props = runCatching { allProperties(devicePath, DEVICE1) }.getOrNull() ?: return
      emitFoundIfKlardrop(devicePath, props)
    }
    if (sig.propertiesChanged["Connected"]?.value == false) {
      val address = knownDevices.remove(devicePath)
      if (address != null) lostListener?.invoke(address)
    }
  }

  /** Emits Found only when the advertisement carries a decodable Klardrop shortDeviceId. */
  private fun emitFoundIfKlardrop(devicePath: String, device: Map<String, Variant<*>>) {
    val address = device["Address"]?.value?.toString() ?: return
    val shortId = decodeShortDeviceId(serviceDataBytes(device["ServiceData"])) ?: return
    val rssi = (device["RSSI"]?.value as? Number)?.toInt() ?: 0
    val localName = device["Name"]?.value?.toString()
    knownDevices[devicePath] = address
    foundListener?.invoke(BlePeerEvent.Found(address, shortId, localName, rssi))
  }

  // ── Connect helpers ───────────────────────────────────────────────────────

  private fun resolveDevicePath(address: String): String {
    val root = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, "/", ObjectManager::class.java)
    return root.GetManagedObjects().entries
      .firstOrNull { (_, interfaces) ->
        interfaces[DEVICE1]?.get("Address")?.value?.toString() == address
      }?.key?.path
      ?: throw IllegalStateException("No BlueZ device with address $address — scan first")
  }

  /** BlueZ resolves services asynchronously after Connect(); poll until ready or give up. */
  private fun awaitServicesResolved(devicePath: String, address: String) {
    val deadline = System.currentTimeMillis() + SERVICES_RESOLVED_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
      val resolved = runCatching {
        property(devicePath, DEVICE1, "ServicesResolved") as? Boolean
      }.getOrNull()
      if (resolved == true) return
      runCatching { Thread.sleep(SERVICES_POLL_MS) }
    }
    throw IllegalStateException("GATT services never resolved for $address")
  }

  /** Locates the Klardrop service's TX/RX characteristics among the managed objects. */
  private fun resolveCharacteristics(devicePath: String, address: String): Pair<String, String> {
    val root = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, "/", ObjectManager::class.java)
    val managed = root.GetManagedObjects()
    val servicePath = managed.entries.firstOrNull { (_, interfaces) ->
      val service = interfaces[GATT_SERVICE1] ?: return@firstOrNull false
      service["UUID"]?.value == BleConstants.SERVICE_UUID &&
        service["Device"]?.value?.toString() == devicePath
    }?.key?.path ?: throw IllegalStateException("Klardrop service missing on $address")
    var txPath: String? = null
    var rxPath: String? = null
    for ((path, interfaces) in managed) {
      val char = interfaces[GATT_CHARACTERISTIC1] ?: continue
      if (char["Service"]?.value?.toString() != servicePath) continue
      when (char["UUID"]?.value as? String) {
        BleConstants.TX_CHARACTERISTIC_UUID -> txPath = path.path
        BleConstants.RX_CHARACTERISTIC_UUID -> rxPath = path.path
      }
    }
    return Pair(
      txPath ?: throw IllegalStateException("TX characteristic missing on $address"),
      rxPath ?: throw IllegalStateException("RX characteristic missing on $address"),
    )
  }

  /** `GattCharacteristic1.MTU` lives on the characteristic, not on the owning Device1. */
  private fun readMtu(characteristicPath: String): Int = negotiatedMtu(
    runCatching {
      (property(characteristicPath, GATT_CHARACTERISTIC1, "MTU") as? Number)?.toInt()
    }.getOrNull(),
  )

  private fun allProperties(path: String, interfaceName: String): Map<String, Variant<*>> {
    val props = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, path, Properties::class.java)
    return runCatching { props.GetAll(interfaceName) }.getOrDefault(emptyMap())
  }

  /**
   * `Properties.Get` declares a type-variable return, and dbus-java unwraps the wire
   * Variant for those — so the value arrives bare. The Variant branch only covers
   * hand-built stubs that hand one back.
   */
  private fun property(path: String, interfaceName: String, name: String): Any? {
    val props = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, path, Properties::class.java)
    return unwrapVariant(props.Get<Any>(interfaceName, name))
  }

  private companion object {
    const val TAG = "BlueZCentralFacade"
    const val DEVICE1 = "org.bluez.Device1"
    const val GATT_SERVICE1 = "org.bluez.GattService1"
    const val GATT_CHARACTERISTIC1 = "org.bluez.GattCharacteristic1"
    const val SERVICES_RESOLVED_TIMEOUT_MS = 5_000L
    const val SERVICES_POLL_MS = 100L
  }
}

/**
 * Match rules for the signals the central role listens to. None of them constrains the
 * sender: BlueZ's signals arrive under its unique bus name (":1.7"), so a rule pinned to
 * the well-known "org.bluez" matches on the daemon but is then dropped by dbus-java's own
 * client-side check — the handler would never run.
 */
internal fun interfacesAddedRule(): DBusMatchRule = DBusMatchRuleBuilder.create()
  .withType(ObjectManager.InterfacesAdded::class.java)
  .build()

internal fun interfacesRemovedRule(): DBusMatchRule = DBusMatchRuleBuilder.create()
  .withType(ObjectManager.InterfacesRemoved::class.java)
  .build()

/** path_namespace covers every /org/bluez/hciX/dev_... device path with one rule. */
internal fun bluezPropertiesChangedRule(): DBusMatchRule = DBusMatchRuleBuilder.create()
  .withType(Properties.PropertiesChanged::class.java)
  .withPathNamespace(BLUEZ_PATH_NAMESPACE)
  .build()

/** PropertiesChanged emitted by the object at [path], whatever its unique sender name. */
internal fun propertiesChangedRule(path: String): DBusMatchRule = DBusMatchRuleBuilder.create()
  .withType(Properties.PropertiesChanged::class.java)
  .withPath(path)
  .build()

internal const val BLUEZ_PATH_NAMESPACE = "/org/bluez"

/**
 * The object path an ObjectManager signal is *about*. dbus-java's `objectPath` is the
 * signal's emitting path — "/" for BlueZ's root ObjectManager, the same for every device —
 * while the added/removed path is the signal's first argument, exposed as `signalSource`.
 */
internal val ObjectManager.InterfacesAdded.changedPath: String get() = signalSource.path

internal val ObjectManager.InterfacesRemoved.changedPath: String get() = signalSource.path

/** Unwraps a [Variant] wrapper if there is one; passes anything else through. */
internal fun unwrapVariant(raw: Any?): Any? = (raw as? Variant<*>)?.value ?: raw

/**
 * Decodes the shortDeviceId from Device1 ServiceData, mirroring `BleAdvertisePayload`'s
 * encoding (UTF-8 bytes of the ≤8-char alphanumeric id keyed under the Klardrop service
 * UUID). Returns null for absent/malformed service data — the device is skipped, never
 * crashed on.
 */
internal fun decodeShortDeviceId(serviceData: Map<String, ByteArray>): String? {
  val bytes = serviceData[BleConstants.SERVICE_UUID] ?: return null
  if (bytes.isEmpty() || bytes.size > MAX_SHORT_DEVICE_ID_LEN) return null
  val id = bytes.decodeToString()
  // decodeToString replaces invalid UTF-8 with U+FFFD; requiring a 1:1 byte→char length
  // plus the generator's alphanumeric charset rejects such garbage.
  if (id.length != bytes.size || id.any { !it.isLetterOrDigit() }) return null
  return id
}

/**
 * Payload bytes per chunk for the session, derived from `GattCharacteristic1.MTU` when
 * BlueZ exposes it (≥ 5.63), else from the conservative ATT default. `BleSession.mtu` is
 * a payload size, not the raw ATT MTU, so the 3-byte ATT header comes off the top — same
 * as Android's `mtu - ATT_HEADER_SIZE` and Apple's `maximumWriteValueLength`. ATT MTUs
 * below the spec minimum are treated as absent.
 */
internal fun negotiatedMtu(mtuProperty: Int?): Int =
  (mtuProperty?.takeIf { it >= BleConstants.DEFAULT_MTU } ?: BleConstants.DEFAULT_MTU) -
    BleConstants.ATT_HEADER_SIZE

/**
 * BlueZ ServiceData is `a{sv}` of string → byte-array variants; depending on the
 * deserialization path the values arrive as byte[] or List<Byte>. Tolerates both,
 * returns an empty map for anything else.
 */
internal fun serviceDataBytes(raw: Any?): Map<String, ByteArray> {
  val outer = unwrapVariant(raw) as? Map<*, *> ?: return emptyMap()
  val result = mutableMapOf<String, ByteArray>()
  for ((key, value) in outer) {
    val name = key as? String ?: continue
    val v = unwrapVariant(value)
    result[name] = when (v) {
      is ByteArray -> v
      is List<*> -> v.map { (it as? Number)?.toByte() ?: 0.toByte() }.toByteArray()
      else -> continue
    }
  }
  return result
}
