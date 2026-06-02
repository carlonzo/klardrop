import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// PermissionsPanelView
// Builds KdPermissionItem list from pending capabilities + educational notes,
// renders them in a warn-toned checklist. Collapses when all granted.
// Mirrors: compose-ui/.../PermissionsPanel.kt
//
// On iOS, relevant capabilities: LOCAL_NETWORK, BLUETOOTH, NOTIFICATIONS.
// ---------------------------------------------------------------------------

struct PermissionsPanelView: View {
    let state: PermissionsState
    let onRequestCapability: (Capability) -> Void

    @State private var dismissedNoteIds: Set<String> = []

    @Environment(\.kdColors) private var kd

    private var pendingCapabilities: [Capability] {
        state.capabilities
            .filter { (_, status) in
                // CapabilityStatus is a SKIE-generated Swift enum — switch directly.
                switch status {
                case .denied, .unknown: return true
                default: return false
                }
            }
            .map { $0.key }
    }

    private var visibleNotes: [EducationalNote] {
        state.educationalNotes.filter { !dismissedNoteIds.contains($0.id) }
    }

    private var items: [KdPermissionItem] {
        var result: [KdPermissionItem] = []
        for cap in pendingCapabilities {
            let (title, caption) = capabilityCopy(cap)
            result.append(KdPermissionItem(
                id: cap.name,
                title: title,
                caption: caption,
                isGranted: false
            ))
        }
        for note in visibleNotes {
            result.append(KdPermissionItem(
                id: note.id,
                title: note.message,
                caption: "",
                isGranted: false
            ))
        }
        return result
    }

    var body: some View {
        if items.isEmpty {
            EmptyView()
        } else {
            PermissionsChecklistView(
                items: items,
                onAllow: { item in
                    if let cap = pendingCapabilities.first(where: { $0.name == item.id }) {
                        onRequestCapability(cap)
                    } else {
                        dismissedNoteIds.insert(item.id)
                    }
                }
            )
            .padding(.horizontal, KdSpacing.s4)
            .padding(.vertical, KdSpacing.s2)
            .animation(.easeOut(duration: 0.25), value: items.count)
        }
    }

    private func capabilityCopy(_ capability: Capability) -> (String, String) {
        switch capability {
        case .localNetwork:
            return ("Local network",
                    "Needed to find other devices on your Wi-Fi.")
        case .bluetooth:
            return ("Bluetooth",
                    "Used as a fallback transport when Wi-Fi reachability isn\u{2019}t enough.")
        case .notifications:
            return ("Notifications",
                    "Lets Klardrop alert you when a transfer arrives.")
        case .nearbyWifiDevices:
            return ("Nearby Wi-Fi devices",
                    "Required on Android 13+ to discover peers without sharing your location.")
        case .location:
            return ("Location",
                    "Required on older Android versions to scan for nearby Bluetooth devices.")
        default:
            return (capability.name, "")
        }
    }
}

// KdPermissionItem is defined in Views/Dialogs/PermissionsChecklistView.swift (dialogs cluster).
