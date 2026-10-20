import Foundation
import presentation

// Called from iosAppTests, inside the app process, so it uses the same
// swift-export framework the root screen links. Reading a non-empty
// collection of Kotlin enums is what aborts at launch (KLARDROP-KJ):
// the generated getter force-casts an NSDictionary/NSSet/NSArray whose
// elements are still Obj-C objects, and that cast fails for Swift enums.
enum SwiftExportBridgeProbe {
    struct Snapshot {
        let educationalNoteCount: Int
        let isComplete: Bool
        let capabilityCount: Int
        let bluetoothDenied: Bool
        let reasonCount: Int
    }

    static func exerciseStartupCollections() -> Snapshot {
        let state = PermissionsState(
            capabilities: [
                Capability.BLUETOOTH: CapabilityStatus.Denied,
                Capability.LOCAL_NETWORK: CapabilityStatus.Unknown,
                Capability.NOTIFICATIONS: CapabilityStatus.Granted,
            ],
            educationalNotes: [
                EducationalNote(id: "probe-note", message: "probe"),
            ]
        )
        let restrictions = ExportedKotlinPackages.com.carlom.klardrop.common.connectivity.ConnectivityRestrictions(
            batterySaverBlocking: true,
            batteryOptimizationNotExempt: true,
            meteredNetworkDenied: false
        )
        return Snapshot(
            educationalNoteCount: state.educationalNotes.count,
            isComplete: state.isComplete,
            capabilityCount: state.capabilities.count,
            bluetoothDenied: state.capabilities[Capability.BLUETOOTH] == CapabilityStatus.Denied,
            reasonCount: restrictions.reasons.count
        )
    }
}
