package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import com.carlom.klardrop.common.discovery.CurrentDevice
import com.carlom.klardrop.common.utils.log
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.types.Variant

/**
 * Real [BlueZFacade] peripheral-role implementation over a live D-Bus connection.
 *
 * Exports the Klardrop GATT object graph (service + TX write + RX notify characteristics)
 * under [GattApplication.APP_PATH_PREFIX] and registers it with
 * `GattManager1.RegisterApplication`. Remote writes arrive as `WriteValue` calls on the
 * exported TX characteristic; notifications are emitted as `PropertiesChanged` on the RX
 * characteristic after the central's `StartNotify`, which BlueZ forwards as ATT
 * notifications to subscribed centrals.
 *
 * ponytail: centrals are attributed via the `device` WriteValue option, falling back to
 * "the single connected Device1" (read from GetManagedObjects at subscribe time). With
 * two centrals connected at once, subscribe attribution degrades to "unknown" — Klardrop
 * pairs 1:1, per-central CCCD tracking would need Device1 signal plumbing.
 */
class BlueZPeripheralFacade(
  private val connection: DBusConnection,
  private val adapterPath: String,
) : BlueZFacade {

  @Volatile private var writeListener: ((String, ByteArray) -> Unit)? = null
  @Volatile private var subscriptionListener: ((String, Boolean) -> Unit)? = null
  @Volatile private var application: GattApplication? = null
  @Volatile private var advertisement: ExportedAdvertisement? = null

  override suspend fun probeCapability(): BlueZCapability = BlueZConnection.probe()

  override fun onCharacteristicWrite(listener: ((String, ByteArray) -> Unit)?) {
    writeListener = listener
  }

  override fun onCentralSubscription(listener: ((String, Boolean) -> Unit)?) {
    subscriptionListener = listener
  }

  override suspend fun exportApplication() = withContext(Dispatchers.IO) {
    check(application == null) { "GATT application already exported" }
    checkAdapterPowered()
    val app = GattApplication(
      onTxWrite = { centralId, value -> writeListener?.invoke(centralId, value) },
      onRxSubscribe = { centralId, subscribed -> subscriptionListener?.invoke(centralId, subscribed) },
      emitSignal = { connection.sendMessage(it) },
      resolveCentralId = { resolveCentralId() },
    )
    try {
      connection.exportObject(app.appPath, app)
      connection.exportObject(app.servicePath, app.service)
      connection.exportObject(app.txPath, app.tx)
      connection.exportObject(app.rxPath, app.rx)
      gattManager().RegisterApplication(DBusPath(app.appPath), emptyMap())
    } catch (e: Exception) {
      unexport(app)
      throw e
    }
    application = app
  }

  override suspend fun unregisterApplication() = withContext(Dispatchers.IO) {
    val app = application ?: return@withContext
    application = null
    runCatching { gattManager().UnregisterApplication(DBusPath(app.appPath)) }
      .onFailure { log(TAG, "UnregisterApplication failed", it) }
    unexport(app)
  }

  override suspend fun notifyValue(centralId: String, value: ByteArray) = withContext(Dispatchers.IO) {
    val app = application ?: throw IllegalStateException("GATT application not exported")
    // BlueZ delivers the PropertiesChanged to every subscribed central; centralId is
    // only meaningful to the session bookkeeping above us.
    app.notifySubscribers(value)
  }

  override suspend fun startAdvertising(currentDevice: CurrentDevice) = withContext(Dispatchers.IO) {
    check(advertisement == null) { "Advertisement already active" }
    checkAdapterPowered()
    val adv = ExportedAdvertisement(currentDevice.shortDeviceId)
    try {
      connection.exportObject(adv.getObjectPath(), adv)
      advertisingManager().RegisterAdvertisement(DBusPath(adv.getObjectPath()), emptyMap())
      log(TAG, "Registered LE advertisement as ${currentDevice.shortDeviceId}")
    } catch (e: Exception) {
      runCatching { connection.unExportObject(adv.getObjectPath()) }
      throw e
    }
    advertisement = adv
  }

  override suspend fun stopAdvertising() {
    withContext(Dispatchers.IO) {
      val adv = advertisement ?: return@withContext
      advertisement = null
      runCatching { advertisingManager().UnregisterAdvertisement(DBusPath(adv.getObjectPath())) }
        .onFailure { log(TAG, "UnregisterAdvertisement failed", it) }
      runCatching { connection.unExportObject(adv.getObjectPath()) }
    }
  }

  /** Fails early with the plan-documented message when the adapter is powered off. */
  private fun checkAdapterPowered() {
    val powered = runCatching {
      val props = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, adapterPath, Properties::class.java)
      unwrapVariant(props.Get<Any>(ADAPTER1, "Powered"))
    }.getOrNull() as? Boolean
    if (powered == false) {
      log(TAG, "[LinuxBle] adapter $adapterPath not powered")
      throw IllegalStateException(
        "Bluetooth adapter $adapterPath is not powered — powering it on is the desktop environment's job",
      )
    }
  }

  /** The single connected remote Device1 path, or [UNKNOWN_CENTRAL] when ambiguous. */
  private fun resolveCentralId(): String {
    val connected = runCatching {
      val root = connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, "/", ObjectManager::class.java)
      root.GetManagedObjects()
        .filterValues { interfaces ->
          interfaces[DEVICE1]?.get("Connected")?.value == true
        }
        .keys.map { it.path }
    }.getOrDefault(emptyList())
    return connected.singleOrNull() ?: UNKNOWN_CENTRAL.also {
      if (connected.size > 1) log(TAG, "Multiple connected BLE centrals; attributing to '$UNKNOWN_CENTRAL'")
    }
  }

  private fun gattManager(): GattManager1 =
    connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, adapterPath, GattManager1::class.java)

  private fun advertisingManager(): LEAdvertisingManager1 =
    connection.getRemoteObject(BlueZConnection.BLUEZ_SERVICE, adapterPath, LEAdvertisingManager1::class.java)

  private fun unexport(app: GattApplication) {
    listOf(app.appPath, app.servicePath, app.txPath, app.rxPath).forEach { path ->
      runCatching { connection.unExportObject(path) }
    }
  }

  private companion object {
    const val TAG = "BlueZPeripheralFacade"
    const val DEVICE1 = "org.bluez.Device1"
    const val ADAPTER1 = "org.bluez.Adapter1"
    const val UNKNOWN_CENTRAL = "unknown"
  }
}

/**
 * The exported GATT application object graph. The application root implements
 * ObjectManager so BlueZ can walk the tree during RegisterApplication; the service and
 * characteristic objects additionally serve their own properties over
 * org.freedesktop.DBus.Properties, which BlueZ reads for anything it did not take from
 * GetManagedObjects. Class-level @DBusProperty only feeds dbus-java's introspection XML —
 * it does NOT make an exported object answer Get/GetAll — so the property map is spelled
 * out here (see [DBusProperties]).
 */
internal class GattApplication(
  private val onTxWrite: (String, ByteArray) -> Unit,
  private val onRxSubscribe: (String, Boolean) -> Unit,
  private val emitSignal: (Message) -> Unit,
  private val resolveCentralId: () -> String,
) : ObjectManager {

  val appPath = APP_PATH_PREFIX
  val servicePath = "$appPath/service0"
  val txPath = "$servicePath/char0"
  val rxPath = "$servicePath/char1"

  val service = ExportedGattService(servicePath)
  val tx = ExportedGattCharacteristic(
    path = txPath,
    uuid = BleConstants.TX_CHARACTERISTIC_UUID,
    servicePath = servicePath,
    flags = TX_FLAGS,
    onWrite = onTxWrite,
    resolveCentralId = resolveCentralId,
  )
  val rx = ExportedGattCharacteristic(
    path = rxPath,
    uuid = BleConstants.RX_CHARACTERISTIC_UUID,
    servicePath = servicePath,
    flags = RX_FLAGS,
    onSubscribe = onRxSubscribe,
    emitSignal = emitSignal,
    resolveCentralId = resolveCentralId,
  )

  override fun getObjectPath() = appPath

  /** Same property maps the objects serve over Properties.GetAll — one source of truth. */
  override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> = mapOf(
    DBusPath(servicePath) to mapOf(GATT_SERVICE1_INTERFACE to service.properties()),
    DBusPath(txPath) to mapOf(GATT_CHARACTERISTIC1_INTERFACE to tx.properties()),
    DBusPath(rxPath) to mapOf(GATT_CHARACTERISTIC1_INTERFACE to rx.properties()),
  )

  /** Updates the RX value and emits PropertiesChanged for subscribed centrals. */
  fun notifySubscribers(value: ByteArray) = rx.notifySubscribers(value)

  companion object {
    const val APP_PATH_PREFIX = "/com/carlom/klardrop/ble"
    val TX_FLAGS = listOf("write", "write-without-response")
    val RX_FLAGS = listOf("read", "notify")
  }
}

/**
 * Serves a fixed property map over org.freedesktop.DBus.Properties for an exported
 * object. dbus-java only auto-answers Get/GetAll for methods annotated
 * `@DBusBoundProperty`; a class-level `@DBusProperty` is introspection metadata only, so
 * without this an exported object replies UnknownProperty/UnknownMethod to BlueZ — which
 * is fatal for LEAdvertisement1, whose whole payload BlueZ reads via GetAll.
 *
 * All Klardrop-exported properties are read-only: Set always fails.
 */
internal abstract class DBusProperties(private val servedInterface: String) : Properties {

  /** The property map served for [servedInterface], re-read per call (Value changes). */
  abstract fun properties(): Map<String, Variant<*>>

  @Suppress("UNCHECKED_CAST")
  override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
    (properties()[propertyName] ?: throw UnknownProperty("$interfaceName.$propertyName")) as A

  override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A): Unit =
    throw PropertyReadOnly("$interfaceName.$propertyName is read-only")

  /** An unknown interface gets an empty dict, per the D-Bus Properties specification. */
  override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
    if (interfaceName.isEmpty() || interfaceName == servedInterface) properties() else emptyMap()
}

internal class ExportedGattService(
  private val path: String,
  private val uuid: String = BleConstants.SERVICE_UUID,
) : GattService1, DBusProperties(GATT_SERVICE1_INTERFACE) {

  override fun getObjectPath() = path

  override fun properties(): Map<String, Variant<*>> = mapOf(
    "UUID" to Variant(uuid),
    "Primary" to Variant(true),
  )
}

internal class ExportedGattCharacteristic(
  private val path: String,
  private val uuid: String,
  private val servicePath: String,
  private val flags: List<String>,
  private val onWrite: ((String, ByteArray) -> Unit)? = null,
  private val onSubscribe: ((String, Boolean) -> Unit)? = null,
  private val emitSignal: (Message) -> Unit = {},
  private val resolveCentralId: () -> String,
) : GattCharacteristic1, DBusProperties(GATT_CHARACTERISTIC1_INTERFACE) {

  @Volatile private var value: ByteArray = ByteArray(0)
  @Volatile private var notifying = false

  override fun getObjectPath() = path

  override fun properties(): Map<String, Variant<*>> = mapOf(
    "UUID" to Variant(uuid),
    "Service" to Variant(DBusPath(servicePath)),
    "Flags" to Variant(flags, "as"),
    "Value" to Variant(value, "ay"),
    "Notifying" to Variant(notifying),
  )

  override fun ReadValue(options: Map<String, Variant<*>>): ByteArray = value

  override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
    this.value = value
    val centralId = unwrapVariant(options["device"])?.toString() ?: resolveCentralId()
    onWrite?.invoke(centralId, value)
  }

  override fun StartNotify() {
    notifying = true
    onSubscribe?.invoke(resolveCentralId(), true)
  }

  override fun StopNotify() {
    notifying = false
    onSubscribe?.invoke(resolveCentralId(), false)
  }

  /** Updates Value and emits PropertiesChanged so BlueZ notifies subscribed centrals. */
  fun notifySubscribers(value: ByteArray) {
    this.value = value
    if (!notifying) return
    emitSignal(
      Properties.PropertiesChanged(
        path,
        GATT_CHARACTERISTIC1_INTERFACE,
        mapOf("Value" to Variant(value, "ay")),
        emptyList(),
      ),
    )
  }
}

/**
 * The exported LEAdvertisement1. BlueZ reads the whole advertisement through
 * `Properties.GetAll` during RegisterAdvertisement, so [DBusProperties] is what puts the
 * payload on the air. Mirrors `klardropAdvertisePayload`: service UUID in the primary
 * advertisement, shortDeviceId as service data — only the 8-char id ever goes on the air.
 */
internal class ExportedAdvertisement(
  private val shortDeviceId: String,
  private val path: String = ADVERTISEMENT_PATH,
) : LEAdvertisement1, DBusProperties(LE_ADVERTISEMENT1_INTERFACE) {

  override fun getObjectPath() = path

  /** a{sv} keyed by service UUID; byte-array values carry the explicit "ay" signature. */
  override fun properties(): Map<String, Variant<*>> = mapOf(
    "Type" to Variant("peripheral"),
    "ServiceUUIDs" to Variant(listOf(BleConstants.SERVICE_UUID), "as"),
    "ServiceData" to Variant(
      mapOf(BleConstants.SERVICE_UUID to Variant(shortDeviceId.encodeToByteArray(), "ay")),
      "a{sv}",
    ),
    "LocalName" to Variant(shortDeviceId),
    "Includes" to Variant(listOf("tx-power"), "as"),
  )

  /** BlueZ releases the advertisement itself on adapter power-off; nothing to reclaim. */
  override fun Release() {
    log(TAG, "Advertisement released by BlueZ")
  }

  private companion object {
    const val TAG = "ExportedAdvertisement"
    const val ADVERTISEMENT_PATH = "/com/carlom/klardrop/ble/advertisement0"
  }
}

internal const val GATT_SERVICE1_INTERFACE = "org.bluez.GattService1"
internal const val GATT_CHARACTERISTIC1_INTERFACE = "org.bluez.GattCharacteristic1"
internal const val LE_ADVERTISEMENT1_INTERFACE = "org.bluez.LEAdvertisement1"
