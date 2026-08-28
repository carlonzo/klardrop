package com.carlom.klardrop.common.ble.linux

import kotlinx.coroutines.test.runTest
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Capability-probe logic for BlueZ, exercised through a fake [BlueZFacade] built from
 * synthetic ObjectManager.GetManagedObjects() data — no D-Bus daemon involved.
 */
class BlueZCapabilityTest {

  private fun adapter(vararg interfaces: String): Map<String, Map<String, Variant<*>>> =
    interfaces.associateWith { emptyMap() }

  private fun managedObjects(
    vararg entries: Pair<String, Map<String, Map<String, Variant<*>>>>,
  ): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
    entries.associate { (path, interfaces) -> DBusPath(path) to interfaces }

  /** Fake facade mirroring BlueZConnection.probe(): null managed objects == no system bus. */
  private class FakeBlueZFacade(
    private val managedObjects: Map<DBusPath, Map<String, Map<String, Variant<*>>>>?,
  ) : BlueZFacade {
    override suspend fun probeCapability(): BlueZCapability {
      val managed = managedObjects ?: return BlueZCapability(supported = false, adapterPaths = emptyList())
      val adapters = BlueZConnection.capableAdapters(managed)
      return BlueZCapability(supported = adapters.isNotEmpty(), adapterPaths = adapters.map { it.path })
    }
  }

  @Test
  fun adapterWithBothManagersIsSupported() = runTest {
    val facade = FakeBlueZFacade(
      managedObjects(
        "/org/bluez" to emptyMap(),
        "/org/bluez/hci0" to adapter(
          "org.bluez.Adapter1",
          BlueZConnection.GATT_MANAGER,
          BlueZConnection.LE_ADVERTISING_MANAGER,
        ),
      ),
    )
    val capability = facade.probeCapability()
    assertTrue(capability.supported)
    assertEquals(listOf("/org/bluez/hci0"), capability.adapterPaths)
  }

  @Test
  fun adapterMissingAdvertisingManagerIsNotSupported() = runTest {
    val facade = FakeBlueZFacade(
      managedObjects(
        "/org/bluez/hci0" to adapter("org.bluez.Adapter1", BlueZConnection.GATT_MANAGER),
      ),
    )
    assertFalse(facade.probeCapability().supported)
  }

  @Test
  fun noSystemBusIsNotSupported() = runTest {
    val facade = FakeBlueZFacade(managedObjects = null)
    val capability = facade.probeCapability()
    assertFalse(capability.supported)
    assertTrue(capability.adapterPaths.isEmpty())
  }

  @Test
  fun onlyFullyCapableAdaptersAreListed() = runTest {
    val facade = FakeBlueZFacade(
      managedObjects(
        "/org/bluez/hci0" to adapter(
          "org.bluez.Adapter1",
          BlueZConnection.GATT_MANAGER,
          BlueZConnection.LE_ADVERTISING_MANAGER,
        ),
        "/org/bluez/hci1" to adapter("org.bluez.Adapter1", BlueZConnection.GATT_MANAGER),
      ),
    )
    val capability = facade.probeCapability()
    assertTrue(capability.supported)
    assertEquals(listOf("/org/bluez/hci0"), capability.adapterPaths)
  }
}
