package com.carlom.klardrop.android

import android.util.Log
import java.io.File

/**
 * Optional per-launch flags for debug builds, read from `<filesDir>/klardrop-debug.json`
 * before [KlarDropApplication] constructs [com.carlom.klardrop.common.Klardrop].
 *
 * Written by `scripts/klardrop-ctl` via `adb shell run-as`. Missing file → all transports on,
 * control port 8766.
 */
data class AndroidDebugConfig(
  val controlPort: Int = 8766,
  val enableKlardrop: Boolean = true,
  val enableNearby: Boolean = true,
  val enableBle: Boolean = true,
) {
  companion object {
    const val FILE_NAME = "klardrop-debug.json"
    private const val TAG = "AndroidDebugConfig"

    fun load(filesDir: File): AndroidDebugConfig {
      val file = File(filesDir, FILE_NAME)
      if (!file.isFile) return AndroidDebugConfig()
      return try {
        val text = file.readText()
        AndroidDebugConfig(
          controlPort = intField(text, "controlPort") ?: 8766,
          enableKlardrop = boolField(text, "enableKlardrop") ?: true,
          enableNearby = boolField(text, "enableNearby") ?: true,
          enableBle = boolField(text, "enableBle") ?: true,
        )
      } catch (e: Exception) {
        Log.w(TAG, "Failed to parse ${file.absolutePath}: ${e.message}")
        AndroidDebugConfig()
      }
    }

    private fun intField(json: String, key: String): Int? =
      Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun boolField(json: String, key: String): Boolean? =
      Regex("\"$key\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
  }
}
