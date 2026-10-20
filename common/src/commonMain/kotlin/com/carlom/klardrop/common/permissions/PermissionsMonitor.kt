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
public expect class PermissionsMonitor {
  public fun observe(): Flow<PermissionsState>

  /**
   * Force a re-read of the current permission state. The OS prompt the platform
   * app shows in-app (Android's runtime permission dialog) only *pauses* the
   * host Activity — it never stops it — so granting a permission produces no
   * foreground transition for [observe] to react to. The app calls this right
   * after such a prompt returns so the panel reflects the new grant immediately
   * instead of going stale until the next background→foreground round-trip.
   */
  public fun refresh(): Unit
}

/**
 * One capability and the status the platform last observed for it.
 *
 * This is a class on purpose. swift-export bridges a Kotlin [Map] as an
 * `NSDictionary` and then force-casts it to `Dictionary<Key, Value>`. A Kotlin
 * enum is a Swift enum, but the dictionary still holds the Obj-C object, boxed
 * as `AnyHashable`, so `Map<Capability, CapabilityStatus>` aborts in that cast
 * (KLARDROP-KJ). A list of these records bridges as an array of objects, and
 * each enum is read back through its own property.
 */
public data class CapabilityGrant(
  public val capability: Capability,
  public val status: CapabilityStatus,
)

public data class PermissionsState(
  public val capabilities: List<CapabilityGrant> = emptyList(),
  public val educationalNotes: List<EducationalNote> = emptyList(),
) {
  /** True when nothing actionable is missing — drives panel auto-hide. */
  public val isComplete: Boolean
    get() = capabilities.all {
      it.status == CapabilityStatus.Granted || it.status == CapabilityStatus.NotApplicable
    } && educationalNotes.isEmpty()

  public fun statusOf(capability: Capability): CapabilityStatus? =
    capabilities.firstOrNull { it.capability == capability }?.status

  public companion object {
    public val EMPTY: PermissionsState = PermissionsState()
  }
}

/**
 * Capabilities Klardrop needs at the OS-permission level. Each [Capability]
 * maps onto whatever underlying permission(s) the platform exposes; for
 * platforms that don't surface a given capability as a permission (e.g. mDNS
 * on Linux JVM is unrestricted), that capability simply isn't included.
 */
public enum class Capability {
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

public enum class CapabilityStatus {
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
public data class EducationalNote(
  public val id: String,
  public val message: String,
)
