import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// KlardropNav — Size-class router.
//
// COMPACT (iPhone):
//   NavigationStack(path: $model.path) with DiscoveryScreen as root.
//   .navigationDestination(for: ChatRoute.self) -> DeviceChatScreen.
//   onNavigateToChat appends the route AND calls setActiveChatDeviceId.
//   On pop (onDisappear from the chat screen) activeChatDeviceId is cleared.
//
// REGULAR (iPad / WideLayout parity):
//   NavigationSplitView { sidebar } detail: { chat or WideEmptyPaneView }.
//   Sidebar reuses SidebarView from the device-list cluster.
//   Selecting a row sets model.selectedChat and calls setActiveChatDeviceId.
//   Detail is id-keyed on route.deviceId so ChatModel recreates on change.
//
// GLOBAL OVERLAYS:
//   PairingApprovalSheet is attached here (survives push/pop/selection changes).
//
// Mirrors: KlardropNavigator.kt + WideLayout.kt structure.
// ---------------------------------------------------------------------------

struct KlardropNav: View {

    let model: DiscoveryAppModel

    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.kdColors) private var kd

    var body: some View {
        Group {
            if sizeClass == .compact {
                compactLayout
            } else {
                regularLayout
            }
        }
        // Global pairing sheet — survives push/selection changes.
        .sheet(item: Binding(
            get: { model.state.pairingDialogState.map(PairingDialogStateWrapper.init) },
            set: { _ in model.dismissPairing() }
        )) { wrapper in
            PairingApprovalSheet(
                state: wrapper.state,
                onDismiss: { model.dismissPairing() }
            )
        }
    }

    // MARK: - Compact (iPhone) — NavigationStack

    private var compactLayout: some View {
        NavigationStack(path: Binding(
            get: { model.path },
            set: { model.path = $0 }
        )) {
            DiscoveryScreen(
                model: model,
                onNavigateToChat: { deviceId, deviceName in
                    let route = ChatRoute(deviceId: deviceId, deviceName: deviceName)
                    model.navigateToChat(route)
                }
            )
            .navigationDestination(for: ChatRoute.self) { route in
                let isOwned = isDeviceTrusted(deviceId: route.deviceId)
                DeviceChatScreen(
                    model: ChatModel(deviceId: route.deviceId, bootstrap: model.bootstrap),
                    deviceName: route.deviceName,
                    isOwned: isOwned
                )
                .id(route.deviceId)
                .onDisappear {
                    // When the chat screen disappears it has been popped from the
                    // stack (back swipe / button). Clear the active chat device so
                    // unread badges resume and the controller tears down the chat scope.
                    model.setActiveChatDeviceId(nil)
                }
            }
        }
    }

    // MARK: - Regular (iPad) — NavigationSplitView

    private var regularLayout: some View {
        NavigationSplitView(
            columnVisibility: .constant(.all)
        ) {
            iPadSidebar
                .navigationTitle("Klardrop")
                .navigationBarTitleDisplayMode(.inline)
        } detail: {
            if let route = model.selectedChat {
                let isOwned = isDeviceTrusted(deviceId: route.deviceId)
                DeviceChatScreen(
                    model: ChatModel(deviceId: route.deviceId, bootstrap: model.bootstrap),
                    deviceName: route.deviceName,
                    isOwned: isOwned
                )
                .id(route.deviceId)
            } else {
                WideEmptyPaneView()
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    // MARK: - iPad sidebar content
    //
    // Uses SidebarView's yoursSection/nearbySection closures directly so that
    // the system NavigationSplitView handles the column chrome while SidebarView
    // provides the scrollable device rows and local-device footer.
    // The SidebarView's fixed `width:` frame is omitted here by calling it
    // without a width so it fills the sidebar column.

    @ViewBuilder
    private var iPadSidebar: some View {
        let state = model.state
        let trusted = state.devices.filter { isTrusted($0) }
        let nearby = state.devices.filter { !isTrusted($0) }
        let localName: String = {
            if let n = state.currentDeviceName, !n.isEmpty { return n }
            return state.systemDeviceName ?? "This Device"
        }()

        VStack(spacing: 0) {
            // Scrollable device sections
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // "My devices" section
                    SectionHeadView(label: "My devices")
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.top, KdSpacing.s3)

                    if trusted.isEmpty {
                        Text("No trusted devices")
                            .kdStyle(.caption, color: kd.text3)
                            .padding(.horizontal, KdSpacing.s5)
                            .padding(.vertical, KdSpacing.s2)
                    } else {
                        ForEach(trusted, id: \.deviceId) { device in
                            sidebarRow(device: device)
                        }
                    }

                    Spacer().frame(height: KdSpacing.s4)

                    // "Nearby" section
                    SectionHeadView(label: "Nearby")
                        .padding(.horizontal, KdSpacing.s3)

                    if nearby.isEmpty {
                        Text("Scanning\u{2026}")
                            .kdStyle(.caption, color: kd.text3)
                            .padding(.horizontal, KdSpacing.s5)
                            .padding(.vertical, KdSpacing.s2)
                    } else {
                        ForEach(nearby, id: \.deviceId) { device in
                            sidebarRow(device: device)
                        }
                    }

                    Spacer().frame(height: KdSpacing.s2)
                }
            }

            Divider().background(kd.border)

            // Local device footer (tap to rename)
            Button {
                // Rename is surfaced via DiscoveryScreen header on compact.
                // On iPad this footer is a secondary affordance; no-op for now.
            } label: {
                HStack(spacing: KdSpacing.s3) {
                    DeviceAvatarView(kind: .mac, style: .tinted, status: .ok, size: 40)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(localName.isEmpty ? "This device" : localName)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(kd.text)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "pencil")
                        .font(.system(size: 14, weight: .regular))
                        .foregroundColor(kd.text3)
                }
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s3)
            }
            .buttonStyle(.plain)
        }
        .background(kd.bg1)
        .scrollContentBackground(.hidden)
    }

    // MARK: - Sidebar device row helper

    @ViewBuilder
    private func sidebarRow(device: DeviceUi) -> some View {
        let isSelected = model.selectedChat?.deviceId == device.deviceId
        let trusted = isTrusted(device)

        DeviceRowView(
            name: device.deviceName,
            subText: device.subText,
            kind: device.deviceKind,
            avatarStyle: trusted ? .tinted : .neutral,
            rowState: isSelected ? .active : device.rowState,
            status: device.kdStatus,
            onTap: {
                let route = ChatRoute(deviceId: device.deviceId, deviceName: device.deviceName)
                model.selectedChat = route
                model.onDeviceTap(device)
                model.setActiveChatDeviceId(device.deviceId)
            }
        )
        .padding(.horizontal, KdSpacing.s2)
    }

    // MARK: - Helpers

    private func isTrusted(_ device: DeviceUi) -> Bool {
        switch onEnum(of: device.trustStatus) {
        case .trusted: return true
        default: return false
        }
    }

    private func isDeviceTrusted(deviceId: String) -> Bool {
        guard let device = model.state.devices.first(where: { $0.deviceId == deviceId }) else {
            return false
        }
        return isTrusted(device)
    }
}

// MARK: - PairingDialogStateWrapper

/// Identifiable wrapper over PairingDialogState so .sheet(item:) can be used.
private struct PairingDialogStateWrapper: Identifiable {
    let state: PairingDialogState
    var id: String { state.deviceId }

    init(_ state: PairingDialogState) {
        self.state = state
    }
}

// MARK: - WideEmptyPaneView

/// Placeholder shown in the iPad split-view detail column when no device is selected.
/// Mirrors WideLayout.kt WideEmptyPane.
struct WideEmptyPaneView: View {

    @Environment(\.kdColors) private var kd

    var body: some View {
        ZStack {
            kd.bg0.ignoresSafeArea()

            VStack(spacing: KdSpacing.s5) {
                // 72pt icon in a trustBg circle (matches WideLayout.kt sizing)
                ZStack {
                    Circle()
                        .fill(kd.trustBg)
                        .frame(width: 72, height: 72)
                    Image(systemName: "laptopcomputer.and.iphone")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 36, height: 36)
                        .foregroundColor(kd.trust)
                }

                VStack(spacing: KdSpacing.s2) {
                    Text("Pick a device to start")
                        .kdStyle(.title, color: kd.text)
                        .multilineTextAlignment(.center)

                    Text("Select any device from the sidebar to open its chat and share text or files.")
                        .kdStyle(.body, color: kd.text2, multiline: true)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 520)
                }
            }
            .padding(KdSpacing.s7)
        }
    }
}
