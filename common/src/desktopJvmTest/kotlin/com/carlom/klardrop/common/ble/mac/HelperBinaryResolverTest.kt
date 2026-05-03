package com.carlom.klardrop.common.ble.mac

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HelperBinaryResolverTest {

  @Test
  fun extractsBundledHelperWhenPresent() {
    val binary = HelperBinaryResolver.resolve()
    if (binary == null) {
      // Resource not bundled in this environment (e.g. running on Linux test host
      // with no native helper). Skip silently.
      return
    }
    assertNotNull(binary)
    assertTrue(binary.exists(), "Resolved binary should exist on disk")
    assertTrue(binary.length() > 0, "Resolved binary should not be empty")
    assertTrue(binary.canExecute(), "Resolved binary should be executable")
  }

  @Test
  fun secondCallReturnsSameStablePath() {
    val a = HelperBinaryResolver.resolve() ?: return
    val b = HelperBinaryResolver.resolve()
    assertNotNull(b)
    // Same SHA-prefixed temp filename → same path; no duplicate extraction.
    assertTrue(a.absolutePath == b.absolutePath)
  }
}
