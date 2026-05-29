package com.carlom.klardrop.common.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemVerTest {

  @Test
  fun newerPatchMinorMajor() {
    assertTrue(isNewerVersion("0.2.1", "0.2.0"))
    assertTrue(isNewerVersion("0.3.0", "0.2.9"))
    assertTrue(isNewerVersion("1.0.0", "0.9.9"))
  }

  @Test
  fun sameOrOlderIsNotNewer() {
    assertFalse(isNewerVersion("0.2.0", "0.2.0"))
    assertFalse(isNewerVersion("0.2.0", "0.2.1"))
    assertFalse(isNewerVersion("0.1.0", "1.0.0"))
  }

  @Test
  fun releaseOutranksPreRelease() {
    // Same core: the release (no suffix) is newer than the pre-release.
    assertTrue(isNewerVersion("0.2.0", "0.2.0-rc1"))
    assertFalse(isNewerVersion("0.2.0-rc1", "0.2.0"))
  }

  @Test
  fun anyRealVersionBeatsLocalDevFallback() {
    // The local build stamps 0.0.0-dev; a real release must always win.
    assertTrue(isNewerVersion("0.1.0", "0.0.0-dev"))
  }

  @Test
  fun tolerantOfVPrefixAndShortForms() {
    assertTrue(isNewerVersion("v0.2.0", "0.1.0"))
    assertTrue(isNewerVersion("1.2", "1.1.9"))
  }

  @Test
  fun unparseableCandidateNeverNewer() {
    assertFalse(isNewerVersion("not-a-version", "0.1.0"))
  }
}
