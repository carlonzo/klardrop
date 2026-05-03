package com.carlom.klardrop.common.ble.mac

import com.carlom.klardrop.common.utils.log
import java.io.File
import java.security.MessageDigest

/**
 * Locates the bundled `klardrop-ble-helper` binary on macOS and extracts it to a
 * stable temp path so it can be exec'd by [MacBleHelperProcess].
 *
 * The binary is shipped as a classpath resource at `native/macos/klardrop-ble-helper`.
 * Extraction is content-addressed (the temp filename includes a SHA-256 prefix of
 * the bytes) so multiple desktop installs and helper upgrades can coexist without
 * stomping each other, and a re-extract is only needed when the contents change.
 */
internal object HelperBinaryResolver {

  private const val RESOURCE_PATH = "native/macos/klardrop-ble-helper"
  private const val TAG = "MacBleHelper"

  /** Returns the absolute path to a runnable helper binary, or null if unavailable. */
  fun resolve(): File? {
    val classLoader = HelperBinaryResolver::class.java.classLoader
    val stream = classLoader.getResourceAsStream(RESOURCE_PATH) ?: run {
      log(TAG, "BLE helper binary not bundled at resource '$RESOURCE_PATH'")
      return null
    }
    val bytes = stream.use { it.readBytes() }
    if (bytes.isEmpty()) {
      log(TAG, "BLE helper resource '$RESOURCE_PATH' is empty")
      return null
    }
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    val target = File(System.getProperty("java.io.tmpdir"), "klardrop-ble-helper-${sha.take(16)}")

    if (!target.exists() || target.length() != bytes.size.toLong()) {
      target.writeBytes(bytes)
      target.setExecutable(true, /* ownerOnly = */ true)
      log(TAG, "Extracted BLE helper to ${target.absolutePath} (${bytes.size} bytes)")
    } else {
      // Re-set executable in case tmpdir umask cleared it.
      target.setExecutable(true, /* ownerOnly = */ true)
    }
    return target
  }
}
