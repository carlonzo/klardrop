package com.carlom.klardrop.common.ble.linux

import com.carlom.klardrop.common.ble.BleConstants
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dbus-java contracts the real BlueZ facades depend on — the parts that can be pinned
 * without a system bus. Every assertion here stands for a failure mode that is invisible
 * in the fake-facade suites because it lives in the D-Bus plumbing itself: a match rule
 * the daemon accepts but dbus-java then filters out client-side, a signal field that
 * carries the emitter rather than the subject, a Variant that is unwrapped for you, or an
 * exported object that cannot answer the property reads BlueZ makes of it.
 */
class BlueZDBusContractTest {

  // ── Match rules ─────────────────────────────────────────────────────────────

  @Test
  fun signalRulesNeverPinTheSender() {
    // BlueZ's signals arrive under its unique bus name (":1.7"). dbus-java re-checks
    // every rule against the incoming message, comparing the sender literally — so a
    // rule carrying sender='org.bluez' silently drops every signal it was meant to catch.
    val rules = listOf(
      interfacesAddedRule(),
      interfacesRemovedRule(),
      bluezPropertiesChangedRule(),
      propertiesChangedRule("/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"),
    )
    rules.forEach { rule ->
      assertEquals(null, rule.sender, "rule must not constrain the sender: $rule")
      assertFalse("sender=" in rule.toString(), "rule must not constrain the sender: $rule")
    }
  }

  @Test
  fun objectManagerRulesMatchTheObjectManagerSignals() {
    val added = interfacesAddedRule()
    assertEquals("org.freedesktop.DBus.ObjectManager", added.getInterface())
    assertEquals("InterfacesAdded", added.member)
    assertEquals("InterfacesRemoved", interfacesRemovedRule().member)
  }

  @Test
  fun devicePropertiesRuleCoversEveryAdapterDevicePath() {
    val rule = bluezPropertiesChangedRule().toString()
    assertTrue("path_namespace='/org/bluez'" in rule, rule)
    assertTrue("member='PropertiesChanged'" in rule, rule)
    // A namespace, not a path: BlueZ merges scan-response ServiceData into any
    // /org/bluez/hciX/dev_... object, and each one emits from its own path.
    assertFalse("path='" in rule, rule)
  }

  @Test
  fun perObjectRuleUsesThePathFieldNotTheBusNameField() {
    // The convenience overload addSigHandler(Class, String, handler) takes the sender's
    // unique bus name in that String and validates it against ^:[0-9]*\.[0-9]*$ — passing
    // an object path there throws InvalidBusNameException before a single signal arrives.
    val path = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF/service0010/char0011"
    assertEquals(path, propertiesChangedRule(path).path)
  }

  // ── Signal fields ───────────────────────────────────────────────────────────

  @Test
  fun interfacesSignalsIdentifyTheDeviceByArgumentNotByEmitter() {
    // BlueZ emits both signals from its ROOT ObjectManager, so objectPath is "/" for
    // every device; the device path is the signal's first argument (signalSource).
    val devicePath = "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF"
    val added = ObjectManager.InterfacesAdded("/", DBusPath(devicePath), emptyMap())
    val removed = ObjectManager.InterfacesRemoved("/", DBusPath(devicePath), listOf("org.bluez.Device1"))

    assertEquals(devicePath, added.changedPath)
    assertEquals(devicePath, removed.changedPath)
    assertEquals("/", added.objectPath, "objectPath is the emitter, and must not key devices")
  }

  // ── Property values ─────────────────────────────────────────────────────────

  @Test
  fun variantUnwrappingTolerantOfBothShapes() {
    // Properties.Get has a type-variable return, and dbus-java unwraps the wire Variant
    // for those — so a `as? Variant<*>` cast on the result is always null.
    assertEquals(true, unwrapVariant(true))
    assertEquals(true, unwrapVariant(Variant(true)))
    assertEquals(null, unwrapVariant(null))
    assertEquals(185, unwrapVariant(185))
  }

  @Test
  fun negotiatedMtuIsAPayloadSizeNotTheRawAttMtu() {
    // BleSession.mtu is what BleChannelBridge chunks at, and the ATT header is not
    // payload — Android subtracts it, Apple's maximumWriteValueLength already has.
    assertEquals(185 - BleConstants.ATT_HEADER_SIZE, negotiatedMtu(185))
    // BlueZ < 5.63 exposes no MTU property, and a sub-minimum value is nonsense.
    assertEquals(BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE, negotiatedMtu(null))
    assertEquals(BleConstants.DEFAULT_MTU - BleConstants.ATT_HEADER_SIZE, negotiatedMtu(7))
  }

  // ── Exported objects ────────────────────────────────────────────────────────

  @Test
  fun advertisementServesItsPayloadOverProperties() {
    // RegisterAdvertisement makes BlueZ read the whole advertisement with GetAll. A
    // class-level @DBusProperty does NOT make an exported object answer that — dbus-java
    // only auto-answers for @DBusBoundProperty methods — so without an explicit
    // Properties implementation BlueZ gets an error and nothing goes on the air.
    val adv = ExportedAdvertisement("abcd1234")
    val props = adv.GetAll(LE_ADVERTISEMENT1_INTERFACE)

    assertEquals("peripheral", props.getValue("Type").value)
    assertEquals(listOf(BleConstants.SERVICE_UUID), props.getValue("ServiceUUIDs").value)
    assertEquals("abcd1234", props.getValue("LocalName").value)

    @Suppress("UNCHECKED_CAST")
    val serviceData = props.getValue("ServiceData").value as Map<String, Variant<*>>
    assertEquals(
      "abcd1234".encodeToByteArray().toList(),
      (serviceData.getValue(BleConstants.SERVICE_UUID).value as ByteArray).toList(),
    )
    // Same encoding the central decodes off the air.
    assertEquals("abcd1234", decodeShortDeviceId(serviceDataBytes(props.getValue("ServiceData"))))
  }

  @Test
  fun exportedObjectsRejectUnknownPropertiesAndWrites() {
    val adv = ExportedAdvertisement("abcd1234")

    assertEquals("peripheral", adv.Get<Variant<*>>(LE_ADVERTISEMENT1_INTERFACE, "Type").value)
    assertFailsWith<UnknownProperty> { adv.Get<Variant<*>>(LE_ADVERTISEMENT1_INTERFACE, "Nope") }
    assertFailsWith<PropertyReadOnly> { adv.Set(LE_ADVERTISEMENT1_INTERFACE, "LocalName", "x") }
    // Per the D-Bus Properties spec, an interface this object does not carry is empty.
    assertEquals(emptyMap<String, Variant<*>>(), adv.GetAll("org.bluez.GattService1"))
  }

  @Test
  fun gattObjectsServeTheSamePropertiesTheyAdvertiseInGetManagedObjects() {
    val app = GattApplication(
      onTxWrite = { _, _ -> },
      onRxSubscribe = { _, _ -> },
      emitSignal = { },
      resolveCentralId = { "dev_A" },
    )
    val managed = app.GetManagedObjects()

    assertEquals(
      managed.getValue(DBusPath(app.servicePath)).getValue(GATT_SERVICE1_INTERFACE),
      app.service.GetAll(GATT_SERVICE1_INTERFACE),
    )
    assertEquals(
      managed.getValue(DBusPath(app.txPath)).getValue(GATT_CHARACTERISTIC1_INTERFACE),
      app.tx.GetAll(GATT_CHARACTERISTIC1_INTERFACE),
    )

    val rx = app.rx.GetAll(GATT_CHARACTERISTIC1_INTERFACE)
    assertEquals(BleConstants.RX_CHARACTERISTIC_UUID, rx.getValue("UUID").value)
    assertEquals(DBusPath(app.servicePath), rx.getValue("Service").value)
    assertEquals(listOf("read", "notify"), rx.getValue("Flags").value)
    assertEquals(false, rx.getValue("Notifying").value)

    // Notifying tracks the central's subscription, and Value tracks what we pushed.
    app.rx.StartNotify()
    app.notifySubscribers(byteArrayOf(7, 7))
    val afterNotify = app.rx.GetAll(GATT_CHARACTERISTIC1_INTERFACE)
    assertEquals(true, afterNotify.getValue("Notifying").value)
    assertEquals(listOf<Byte>(7, 7), (afterNotify.getValue("Value").value as ByteArray).toList())
  }
}
