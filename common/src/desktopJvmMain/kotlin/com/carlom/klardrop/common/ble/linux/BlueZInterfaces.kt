package com.carlom.klardrop.common.ble.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant

/**
 * dbus-java declarations for the BlueZ D-Bus API
 * (https://github.com/bluez/bluez/blob/master/doc/org.bluez.*.rst).
 *
 * Method and property names must match BlueZ exactly — dbus-java maps D-Bus members
 * by name. Properties are declared with @DBusProperty for introspection data; runtime
 * property access goes through org.freedesktop.DBus.Properties (dbus-java `Properties`).
 *
 * org.bluez's ObjectManager (GetManagedObjects / InterfacesAdded / InterfacesRemoved)
 * is dbus-java's built-in org.freedesktop.dbus.interfaces.ObjectManager — not redeclared.
 */

/** String-array D-Bus type ("as") for @DBusProperty, which cannot express generics directly. */
interface StringListType : TypeRef<List<String>>

/** String-keyed variant-map D-Bus type ("a{sv}") for @DBusProperty. */
interface VariantMapType : TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName("org.bluez.Adapter1")
@DBusProperty(name = "Address", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Name", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Alias", type = String::class)
@DBusProperty(name = "Powered", type = Boolean::class)
@DBusProperty(name = "Discoverable", type = Boolean::class)
@DBusProperty(name = "Discovering", type = Boolean::class, access = DBusProperty.Access.READ)
interface Adapter1 : DBusInterface {
  fun SetDiscoveryFilter(properties: Map<String, Variant<*>>)
  fun StartDiscovery()
  fun StopDiscovery()
}

@DBusInterfaceName("org.bluez.Device1")
@DBusProperty(name = "Address", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Name", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Alias", type = String::class)
@DBusProperty(name = "Paired", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Connected", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "ServicesResolved", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "RSSI", type = Short::class, access = DBusProperty.Access.READ)
interface Device1 : DBusInterface {
  fun Connect()
  fun Disconnect()
}

@DBusInterfaceName("org.bluez.GattManager1")
interface GattManager1 : DBusInterface {
  fun RegisterApplication(application: DBusPath, options: Map<String, Variant<*>>)
  fun UnregisterApplication(application: DBusPath)
}

@DBusInterfaceName("org.bluez.LEAdvertisingManager1")
@DBusProperty(name = "ActiveInstances", type = Byte::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedInstances", type = Byte::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedIncludes", type = StringListType::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedSecondaryChannels", type = StringListType::class, access = DBusProperty.Access.READ)
interface LEAdvertisingManager1 : DBusInterface {
  fun RegisterAdvertisement(advertisement: DBusPath, options: Map<String, Variant<*>>)
  fun UnregisterAdvertisement(advertisement: DBusPath)
}

/** GATT service we export in the peripheral role. */
@DBusInterfaceName("org.bluez.GattService1")
@DBusProperty(name = "UUID", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Primary", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Device", type = DBusPath::class, access = DBusProperty.Access.READ)
interface GattService1 : DBusInterface

/** GATT characteristic we export in the peripheral role and consume in the central role. */
@DBusInterfaceName("org.bluez.GattCharacteristic1")
@DBusProperty(name = "UUID", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Service", type = DBusPath::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Value", type = ByteArray::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Notifying", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Flags", type = StringListType::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MTU", type = UInt16::class, access = DBusProperty.Access.READ)
interface GattCharacteristic1 : DBusInterface {
  fun ReadValue(options: Map<String, Variant<*>>): ByteArray
  fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>)
  fun StartNotify()
  fun StopNotify()
}

/** Advertisement object we export and register via LEAdvertisingManager1. */
@DBusInterfaceName("org.bluez.LEAdvertisement1")
@DBusProperty(name = "Type", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "ServiceUUIDs", type = StringListType::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "ServiceData", type = VariantMapType::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "LocalName", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Includes", type = StringListType::class, access = DBusProperty.Access.READ)
interface LEAdvertisement1 : DBusInterface {
  fun Release()
}
