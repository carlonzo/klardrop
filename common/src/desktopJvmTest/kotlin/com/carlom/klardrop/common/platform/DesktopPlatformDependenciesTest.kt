package com.carlom.klardrop.common.platform

import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.utils.mimeType
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

/**
 * Both of these used to shell out (`hostname`, `file --mime-type`). A packaged Linux launch
 * with a stripped PATH turned that into `IOException: Cannot run program`, and for the device
 * name it threw inside CurrentDevice.deviceInfoFlow — the app then advertised nothing at all.
 * These assert the JVM-native paths answer without a subprocess.
 */
class DesktopPlatformDependenciesTest {

  @Test
  fun `device name resolves without spawning a process`() {
    val name = CommonPlatformDependencies.getDeviceName()
    assertTrue(name.isNotBlank(), "expected a non-blank device name")
    assertTrue(!name.endsWith(".local"), "expected .local to be stripped, got: $name")
  }

  @Test
  fun `mime type resolves for a known extension`() {
    val file = File.createTempFile("klardrop-mime", ".txt").apply { deleteOnExit() }
    assertEquals("text/plain", PlatformFile(file).mimeType())
  }
}
