import Foundation
import presentation

// Called from iosAppTests, inside the app process, so it uses the same
// swift-export framework the root screen links. KLARDROP-KJ aborted while
// reading a non-empty Map/Set/List of Kotlin enums: the generated getter
// force-casts an NSDictionary/NSSet/NSArray of Obj-C objects to a Swift
// collection of enums. These reads go through the list-of-records shape
// instead, then touch each enum as its own property.
enum SwiftExportBridgeProbe {
    struct Snapshot {
        let educationalNoteCount: Int
        let isComplete: Bool
        let capabilityCount: Int
        let bluetoothDenied: Bool
        let reasonCount: Int
        let sawBatterySaver: Bool
        let connectionCount: Int
        let sawKlardropConnection: Bool
    }

    static func exerciseStartupCollections() -> Snapshot {
        let state = PermissionsState(
            capabilities: [
                CapabilityGrant(capability: Capability.BLUETOOTH, status: CapabilityStatus.Denied),
                CapabilityGrant(capability: Capability.LOCAL_NETWORK, status: CapabilityStatus.Unknown),
                CapabilityGrant(capability: Capability.NOTIFICATIONS, status: CapabilityStatus.Granted),
            ],
            educationalNotes: [
                EducationalNote(id: "probe-note", message: "probe"),
            ]
        )
        let bluetoothDenied = state.capabilities.contains { grant in
            grant.capability == Capability.BLUETOOTH && grant.status == CapabilityStatus.Denied
        }

        let restrictions = ConnectivityRestrictions(
            batterySaverBlocking: true,
            batteryOptimizationNotExempt: true,
            meteredNetworkDenied: false
        )
        let sawBatterySaver = restrictions.reasons.contains { reason in
            reason.restriction == ConnectivityRestriction.BatterySaverBlocking
        }

        let device = DeviceUi(
            deviceId: "probe-device",
            deviceName: "Probe",
            deviceType: DeviceType.MOBILE,
            connectionTypes: [
                DeviceConnectionKind(type: DeviceConnectionType.KLARDROP),
                DeviceConnectionKind(type: DeviceConnectionType.BLE),
            ]
        )
        let sawKlardrop = device.connectionTypes.contains { kind in
            kind.type == DeviceConnectionType.KLARDROP
        }

        return Snapshot(
            educationalNoteCount: state.educationalNotes.count,
            isComplete: state.isComplete,
            capabilityCount: state.capabilities.count,
            bluetoothDenied: bluetoothDenied,
            reasonCount: restrictions.reasons.count,
            sawBatterySaver: sawBatterySaver,
            connectionCount: device.connectionTypes.count,
            sawKlardropConnection: sawKlardrop
        )
    }
}
