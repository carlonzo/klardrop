package com.carlom.klardrop.common.permissions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PermissionsStateTest {
  @Test
  fun emptyStateIsComplete() {
    assertTrue(PermissionsState.EMPTY.isComplete)
    assertTrue(PermissionsState.EMPTY.capabilities.isEmpty())
    assertNull(PermissionsState.EMPTY.statusOf(Capability.BLUETOOTH))
  }

  @Test
  fun deniedCapabilityIsIncompleteAndReadable() {
    val state = PermissionsState(
      capabilities = listOf(
        CapabilityGrant(Capability.BLUETOOTH, CapabilityStatus.Denied),
        CapabilityGrant(Capability.LOCAL_NETWORK, CapabilityStatus.NotApplicable),
      ),
      educationalNotes = listOf(EducationalNote(id = "note", message = "note")),
    )

    assertFalse(state.isComplete)
    assertEquals(CapabilityStatus.Denied, state.statusOf(Capability.BLUETOOTH))
    assertEquals(CapabilityStatus.NotApplicable, state.statusOf(Capability.LOCAL_NETWORK))
    assertNull(state.statusOf(Capability.NOTIFICATIONS))
    assertEquals(1, state.educationalNotes.size)
  }

  @Test
  fun grantedAndNotApplicableCapabilitiesAreComplete() {
    val state = PermissionsState(
      capabilities = listOf(
        CapabilityGrant(Capability.BLUETOOTH, CapabilityStatus.Granted),
        CapabilityGrant(Capability.NOTIFICATIONS, CapabilityStatus.NotApplicable),
      ),
    )

    assertTrue(state.isComplete)
  }
}
