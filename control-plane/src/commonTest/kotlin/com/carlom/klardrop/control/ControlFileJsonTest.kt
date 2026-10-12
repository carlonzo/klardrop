package com.carlom.klardrop.control

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The wire shape of `control.json`, which every host writes by hand and every client parses.
 *
 * Deliberately in `commonTest`, not in a per-platform fixture: this is a pure string contract
 * with no platform behaviour in it, so it should run on every target rather than only on the
 * host that happens to be under test.
 */
class ControlFileJsonTest {

  @Test
  fun itCarriesThePortTheTokenTheApiVersionAndTheCapabilityList() {
    val json = controlFileJson(8765, "0123456789abcdef0123456789abcdef", PRODUCTION_CAPABILITIES)
    assertTrue(json.contains(""""port":8765"""), json)
    assertTrue(json.contains(""""token":"0123456789abcdef0123456789abcdef""""), json)
    assertTrue(json.contains(""""apiVersion":$API_VERSION"""), json)
    assertTrue(json.contains(""""capabilities":["""), json)
    assertTrue(json.startsWith("{") && json.endsWith("}"), json)
  }

  /**
   * The Rust client refuses to read a control file larger than 4 KiB (`MAX_CONTROL_FILE_BYTES`)
   * and treats a bigger one as invalid metadata, so a future capability that pushes the real
   * document past that ceiling has to fail here rather than silently on every macOS machine.
   */
  @Test
  fun theDocumentStaysInsideTheSizeTheRustClientAccepts() {
    val json = controlFileJson(65535, "f".repeat(64), PRODUCTION_CAPABILITIES + DEBUG_ONLY_CAPABILITIES)
    assertTrue(
      json.length < 4096,
      "control.json is ${json.length} bytes, over the 4096-byte ceiling the Rust client enforces",
    )
  }
}