package com.carlom.klardrop.common.permissions

import kotlinx.coroutines.flow.Flow

/**
 * Per-platform read-only view of the system permissions Klardrop relies on.
 *
 * The monitor stays passive: it observes platform state and reports it. Acting
 * on a missing permission (showing the OS prompt or deep-linking into Settings)
 * needs platform-specific entry points (Activity on Android, NSWorkspace on
 * macOS), so the [com.carlom.klardrop.PermissionsPanel] composable accepts a
 * callback the platform app wires up — the monitor itself never reaches into
 * Activity or window state.
 *
 * Platforms with no useful permission story (e.g. Linux desktop) return an
 * empty [PermissionsState] so the panel never shows.
 */
expect class PermissionsMonitor {
  fun observe(): Flow<PermissionsState>
}

data class PermissionsState(
  val capabilities: Map<Capability, CapabilityStatus> = emptyMap(),
  val educationalNotes: List<EducationalNote> = emptyList(),
) {
  /** True when nothing actionable is missing — drives panel auto-hide. */
  val isComplete: Boolean
    get() = capabilities.values.all {
      it == CapabilityStatus.Granted || it == CapabilityStatus.NotApplicable
    } && educationalNotes.isEmpty()

  companion object {
    val EMPTY = PermissionsState()
  }
}

/**
 * Capabilities Klardrop needs at the OS-permission level. Each [Capability]
 * maps onto whatever underlying permission(s) the platform exposes; for
 * platforms that don't surface a given capability as a permission (e.g. mDNS
 * on Linux JVM is unrestricted), the entry simply isn't included in the map.
 */
enum class Capability {
  /** Local network / mDNS visibility. macOS, iOS. */
  LOCAL_NETWORK,

  /** Bluetooth Low Energy transport. */
  BLUETOOTH,

  /** OS notifications for incoming messages. */
  NOTIFICATIONS,

  /** Android NEARBY_WIFI_DEVICES (T+) — needed for mDNS without LOCATION. */
  NEARBY_WIFI_DEVICES,

  /** Android fine location — pre-T fallback for Wi-Fi peer discovery. */
  LOCATION,
}

enum class CapabilityStatus {
  /** Capability is granted and usable. */
  Granted,

  /** Explicitly denied — re-requesting may not work; deep-link to settings. */
  Denied,

  /** Not yet prompted, or platform can't tell. Worth requesting. */
  Unknown,

  /** Platform doesn't need / surface this capability — treat as fine. */
  NotApplicable,
}

/**
 * Informational note shown alongside actionable capabilities. Used for prompts
 * the OS will throw at us that we can't detect or pre-empt programmatically —
 * the macOS Application Firewall's "Accept Incoming Connections" being the
 * canonical example.
 *
 * [id] is stable so the UI can remember when the user has dismissed it.
 */
data class EducationalNote(
  val id: String,
  val message: String,
)
