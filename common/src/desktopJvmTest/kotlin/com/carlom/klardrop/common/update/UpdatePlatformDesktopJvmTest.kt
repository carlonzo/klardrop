package com.carlom.klardrop.common.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatePlatformDesktopJvmTest {
  @Test
  fun testPlatformUpdateAssetKeyIsLinuxTarball() {
    assertEquals(UpdateChecker.ASSET_LINUX_TARBALL, platformUpdateAssetKey)
    assertEquals("linux-tarball", platformUpdateAssetKey)
  }
}
