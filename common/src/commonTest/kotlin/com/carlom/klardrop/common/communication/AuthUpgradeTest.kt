package com.carlom.klardrop.common.communication

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthUpgradeTest {

  @Test
  fun `an encrypted but unauthenticated link is worth re-dialling`() {
    assertTrue(linkMayNeedAuthUpgrade(isLinkEncrypted = true, isLinkAuthenticated = false, alreadyAttempted = false))
  }

  @Test
  fun `a cleartext link is never re-dialled`() {
    // BLE/plain links can never report authenticated, so without this guard a trusted peer on
    // BLE would be evicted and re-dialled on every single send.
    assertFalse(linkMayNeedAuthUpgrade(isLinkEncrypted = false, isLinkAuthenticated = false, alreadyAttempted = false))
  }

  @Test
  fun `an already authenticated link is left alone`() {
    assertFalse(linkMayNeedAuthUpgrade(isLinkEncrypted = true, isLinkAuthenticated = true, alreadyAttempted = false))
  }

  @Test
  fun `re-dialling is attempted at most once per device`() {
    // Guards the case where the fresh handshake also comes back unauthenticated.
    assertFalse(linkMayNeedAuthUpgrade(isLinkEncrypted = true, isLinkAuthenticated = false, alreadyAttempted = true))
  }
}
