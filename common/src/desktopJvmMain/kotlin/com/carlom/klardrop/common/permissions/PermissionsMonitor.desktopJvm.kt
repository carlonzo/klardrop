package com.carlom.klardrop.common.permissions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * JVM permission story is mostly empty: Linux and Windows don't gate mDNS
 * or BLE behind app-level permissions. macOS desktop is the exception — it
 * pops two system prompts during normal use that we can't programmatically
 * detect from JVM (the only API for Local Network status is native, and the
 * Application Firewall is opaque to apps by design). We surface those as
 * educational notes on macOS so the panel can warn the user once instead of
 * having them get blindsided.
 */
actual class PermissionsMonitor {

  actual fun observe(): Flow<PermissionsState> {
    val isMac = System.getProperty("os.name", "")
      .lowercase()
      .let { it.contains("mac") || it.contains("darwin") }

    val state = if (isMac) {
      PermissionsState(
        capabilities = emptyMap(),
        educationalNotes = listOf(
          EducationalNote(
            id = "macos-local-network",
            message = "macOS will ask for permission to find devices on your local network the first time you launch — tap Allow.",
          ),
          EducationalNote(
            id = "macos-incoming-connections",
            message = "macOS will ask whether to accept incoming connections the first time another device sends to you — tap Allow.",
          ),
        ),
      )
    } else {
      PermissionsState.EMPTY
    }

    return flowOf(state)
  }

  // Desktop permission state is static (educational notes only, no runtime
  // grants to re-read), so there is nothing to refresh.
  actual fun refresh() = Unit
}
