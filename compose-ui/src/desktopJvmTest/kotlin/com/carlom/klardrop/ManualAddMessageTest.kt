package com.carlom.klardrop

import com.carlom.klardrop.common.discovery.ManualAddResult
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualAddMessageTest {
  @Test
  fun messages() {
    assertEquals("Added Pixel. Tap it above to pair.", manualAddMessage(ManualAddResult.Added("Pixel")))
    assertEquals("Pixel is already in the list above.", manualAddMessage(ManualAddResult.AlreadyVisible("Pixel")))
    assertEquals("That's this device.", manualAddMessage(ManualAddResult.ThisDevice))
    assertEquals("A different device answered there. Ask for a fresh code.", manualAddMessage(ManualAddResult.WrongDevice))
    assertEquals(
      "No Klardrop device answered at 192.168.1.37. Check that Klardrop is open on it and both are on the same network.",
      manualAddMessage(ManualAddResult.Unreachable("192.168.1.37")),
    )
    assertEquals("Enter a code like 37 or 12.37, or an IP address like 192.168.1.37.", manualAddMessage(ManualAddResult.InvalidInput))
    assertEquals("This device isn't connected to Wi-Fi or Ethernet.", manualAddMessage(ManualAddResult.NoLanAddress))
  }
}
