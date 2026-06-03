import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// DiscoveryScreen — Compact (iPhone) discovery dashboard.
// Mirrors: compose-ui/.../discovery_screen.kt DiscoveryScreen + DiscoveryDashboard
//
// Hosts: header, UpdateBannerView, PermissionsPanelView, IncomingBannerStackView,
// YourDevices section, Nearby section.
// Sheets: SettingsSheet, RenameSheet, AddDevicePickerSheet, LinkDeviceConfirmDialog,
// ForgetDeviceConfirmDialog, PairingApprovalSheet (via pairingDialogState).
// ---------------------------------------------------------------------------

struct DiscoveryScreen: View {
    let model: DiscoveryAppModel
    let onNavigateToChat: (_ deviceId: String, _ deviceName: String) -> Void

    // MARK: - Local sheet state

    @State private var showRenameSheet = false
    @State private var showAddDevicePicker = false
    @State private var pendingLinkDevice: DeviceUi? = nil
    @State private var pendingForgetDevice: DeviceUi? = nil

    @Environment(\.kdColors) private var kd

    // MARK: - Derived state

    private var currentDeviceName: String {
        model.state.currentDeviceName ?? model.state.systemDeviceName ?? ""
    }

    private var trustedDevices: [DeviceUi] {
        model.state.devices.filter { d in
            switch onEnum(of: d.trustStatus) {
            case .trusted: return true
            default:       return false
            }
        }
    }

    private var nearbyDevices: [DeviceUi] {
        model.state.devices.filter { d in
            switch onEnum(of: d.trustStatus) {
            case .trusted: return false
            default:       return true
            }
        }
    }

    // MARK: - Body

    var body: some View {
        ZStack {
            kd.bg0.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    // Device-identity header — left-aligned, inline at the top of content.
                    DiscoveryHeaderView(
                        currentDeviceName: currentDeviceName,
                        onEditIdentity: { showRenameSheet = true }
                    )
                    // Update banner (renders nothing on iOS unless Available)
                    UpdateBannerView(
                        status: model.updateStatus,
                        installProgress: model.updateInstallProgress,
                        onAction: { model.updateAction($0) },
                        onRestart: { model.updateRestart() }
                    )

                    // Permissions panel (animated in/out)
                    PermissionsPanelView(
                        state: model.permissionsState,
                        onRequestCapability: { model.onRequestCapability($0) }
                    )

                    // Incoming transfer / notification cards
                    IncomingBannerStackView(state: model.state, model: model)

                    // Your Devices section
                    YourDevicesSectionView(
                        trusted: trustedDevices,
                        model: model,
                        onNavigateToChat: onNavigateToChat,
                        onAddDeviceClick: { showAddDevicePicker = true },
                        onForgetClick: { pendingForgetDevice = $0 },
                        onPendingLink: { pendingLinkDevice = $0 }
                    )

                    // Nearby section
                    NearbySectionView(
                        devices: nearbyDevices,
                        model: model,
                        onNavigateToChat: onNavigateToChat,
                        onPendingLink: { pendingLinkDevice = $0 }
                    )

                    Spacer().frame(height: KdSpacing.s6)
                }
            }
        }
        .navigationTitle("")
        #if os(iOS)
        // Header is rendered inline at the top of the content (left-aligned), so the
        // system navigation bar is hidden on the discovery root.
        .toolbar(.hidden, for: .navigationBar)
        #endif
        // Auto-dismiss add-device picker when a device becomes trusted
        .onChange(of: trustedDevices.count) { _, count in
            if count > 0 { showAddDevicePicker = false }
        }
        // Sheets
        .sheet(isPresented: $showRenameSheet) {
            // RenameSheet applies its own presentationDetents/cornerRadius internally.
            RenameSheet(
                currentName: currentDeviceName,
                onDismiss: { showRenameSheet = false },
                onSave: { newName in
                    model.saveCustomDeviceName(newName.isEmpty ? nil : newName)
                    showRenameSheet = false
                }
            )
        }
        .sheet(isPresented: $showAddDevicePicker) {
            // AddDevicePickerSheet applies its own presentationDetents internally.
            AddDevicePickerSheet(
                candidates: nearbyDevices,
                onDismiss: { showAddDevicePicker = false },
                onPick: { device in
                    showAddDevicePicker = false
                    pendingLinkDevice = device
                }
            )
        }
        .sheet(item: $pendingLinkDevice, onDismiss: { pendingLinkDevice = nil }) { device in
            // LinkDeviceConfirmDialog applies its own presentationDetents internally.
            LinkDeviceConfirmDialog(
                device: device,
                onConfirm: {
                    model.addToTrusted(device)
                    pendingLinkDevice = nil
                },
                onDismiss: { pendingLinkDevice = nil }
            )
        }
        .sheet(item: $pendingForgetDevice, onDismiss: { pendingForgetDevice = nil }) { device in
            // ForgetDeviceConfirmDialog applies its own presentationDetents internally.
            ForgetDeviceConfirmDialog(
                device: device,
                onConfirm: {
                    model.forgetDevice(device)
                    pendingForgetDevice = nil
                },
                onDismiss: { pendingForgetDevice = nil }
            )
        }
        // NOTE: PairingApprovalSheet is attached at the KlardropNav level (global overlay)
        // so it survives push/pop navigation. No duplicate here.
    }
}

// Make DeviceUi conform to Identifiable for .sheet(item:)
extension DeviceUi: @retroactive Identifiable {
    public var id: String { deviceId }
}

// MARK: - DiscoveryHeaderView

struct DiscoveryHeaderView: View {
    let currentDeviceName: String
    let onEditIdentity: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        HStack(spacing: KdSpacing.s2) {
            // Tappable title + device name (rename), pinned to the left.
            Button(action: onEditIdentity) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Klardrop")
                        .kdStyle(.title, color: kd.text)
                        .lineLimit(1)
                    HStack(spacing: KdSpacing.s2) {
                        Circle()
                            .fill(kd.trust)
                            .frame(width: KdSpacing.s2, height: KdSpacing.s2)
                        Text(currentDeviceName.isEmpty ? "This device" : currentDeviceName)
                            .kdStyle(.caption, color: kd.text2)
                            .lineLimit(1)
                    }
                }
            }
            .buttonStyle(.plain)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, KdSpacing.s4)
        .padding(.vertical, KdSpacing.s3)
    }
}

// MARK: - YourDevicesSectionView

private struct YourDevicesSectionView: View {
    let trusted: [DeviceUi]
    let model: DiscoveryAppModel
    let onNavigateToChat: (String, String) -> Void
    let onAddDeviceClick: () -> Void
    let onForgetClick: (DeviceUi) -> Void
    let onPendingLink: (DeviceUi) -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: 0) {
            SectionHeadView(label: "Your devices") {
                if trusted.isEmpty {
                    Button(action: onAddDeviceClick) {
                        Text("Pair a device")
                            .kdStyle(.caption, color: kd.accent)
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, KdSpacing.s1)
                }
            }

            if trusted.isEmpty {
                AddDevicePlaceholderSurface(onClick: onAddDeviceClick)
                    .padding(.horizontal, KdSpacing.s3)
                    .padding(.bottom, KdSpacing.s2)
            } else {
                ForEach(trusted, id: \.deviceId) { device in
                    DeviceRowView(
                        name: device.deviceName,
                        subText: device.subText,
                        kind: device.deviceKind,
                        avatarStyle: .tinted,
                        rowState: device.rowState,
                        status: device.reachabilityStatus,
                        onTap: {
                            model.onDeviceTap(device)
                            onNavigateToChat(device.deviceId, device.deviceName)
                        }
                    ) {
                        HStack(spacing: KdSpacing.s2) {
                            if device.hasUnreadMessages {
                                UnreadBadgeView()
                            }
                            TrustedDeviceMenuView(onForget: { onForgetClick(device) })
                        }
                    }
                    .padding(.horizontal, KdSpacing.s3)
                }
            }
        }
    }
}

// MARK: - NearbySectionView

private struct NearbySectionView: View {
    let devices: [DeviceUi]
    let model: DiscoveryAppModel
    let onNavigateToChat: (String, String) -> Void
    let onPendingLink: (DeviceUi) -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: 0) {
            SectionHeadView(label: "Nearby") {
                ScanningTickerView()
            }

            if devices.isEmpty {
                NearbyEmptyHintView()
                    .padding(.horizontal, KdSpacing.s3)
                    .padding(.bottom, KdSpacing.s2)
            } else {
                ForEach(devices, id: \.deviceId) { device in
                    DeviceRowView(
                        name: device.deviceName,
                        subText: device.subText,
                        kind: device.deviceKind,
                        avatarStyle: .neutral,
                        rowState: device.rowState,
                        status: device.reachabilityStatus,
                        onTap: {
                            model.onDeviceTap(device)
                            onNavigateToChat(device.deviceId, device.deviceName)
                        }
                    ) {
                        // Show Pair button for untrusted / unknown
                        let showPair: Bool = {
                            switch onEnum(of: device.trustStatus) {
                            case .untrusted, .unknown:
                                return true
                            default:
                                return false
                            }
                        }()
                        if showPair {
                            PairButtonView(onClick: { onPendingLink(device) })
                        }
                    }
                    .padding(.horizontal, KdSpacing.s3)
                }
            }
        }
    }
}

// MARK: - Small component helpers

/// Animated "scanning" label shown in the Nearby section header.
private struct ScanningTickerView: View {
    @State private var alpha: Double = 0.3
    @Environment(\.kdColors) private var kd

    var body: some View {
        Text("scanning")
            .kdStyle(.caption, color: kd.text3.opacity(alpha))
            .onAppear {
                withAnimation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true)) {
                    alpha = 1.0
                }
            }
    }
}

/// Empty state hint inside the Nearby section.
private struct NearbyEmptyHintView: View {
    @Environment(\.kdColors) private var kd

    var body: some View {
        Text("No devices nearby. Make sure Klardrop is open on the same Wi-Fi.")
            .kdStyle(.caption, color: kd.text3, multiline: true)
            .multilineTextAlignment(.center)
            .padding(KdSpacing.s4)
            .frame(maxWidth: .infinity)
            .overlay(
                KdShape.lg
                    .strokeBorder(kd.border, lineWidth: 1)
            )
            .clipShape(KdShape.lg)
    }
}

/// Compact "+ Pair" chip button used in the Nearby device row trailing.
private struct PairButtonView: View {
    let onClick: () -> Void
    @Environment(\.kdColors) private var kd

    var body: some View {
        Button(action: onClick) {
            Text("+")
                .kdStyle(.body, color: kd.text2)
                .padding(.horizontal, KdSpacing.s2)
                .padding(.vertical, KdSpacing.s1)
                .background(kd.bg2)
                .clipShape(KdShape.md)
        }
        .buttonStyle(.plain)
    }
}

/// Small accent dot badge for unread messages.
private struct UnreadBadgeView: View {
    @Environment(\.kdColors) private var kd

    var body: some View {
        Circle()
            .fill(kd.accent)
            .frame(width: KdSpacing.s2, height: KdSpacing.s2)
    }
}

/// "..." context menu for trusted devices (Forget action).
private struct TrustedDeviceMenuView: View {
    let onForget: () -> Void
    @Environment(\.kdColors) private var kd

    var body: some View {
        Menu {
            Button(role: .destructive, action: onForget) {
                Label("Forget this device", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: KdSpacing.s4, weight: .regular))
                .foregroundColor(kd.text2)
                .frame(width: KdSpacing.s6, height: KdSpacing.s6)
        }
    }
}

// AddDevicePlaceholderSurface is defined in Views/Dialogs/AddDevicePickerSheet.swift (dialogs cluster).
