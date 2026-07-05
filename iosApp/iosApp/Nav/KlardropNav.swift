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
//   The sidebar content is built inline (iPadSidebar) using DeviceRowView.
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

    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif
    @Environment(\.kdColors) private var kd

    /// Drives the rename sheet presented from the iPad sidebar footer button.
    @State private var showRenameSheet = false

    /// Add-device flow (mirrors DiscoveryScreen / Compose WideLayout): the picker lists
    /// nearby untrusted candidates; picking one opens the link-confirm dialog, which on
    /// confirm calls model.addToTrusted to start the pairing/trust flow.
    @State private var showAddDevicePicker = false
    @State private var pendingLinkDevice: DeviceUi? = nil
    /// Trusted device awaiting forget confirmation (long-press / context menu on a sidebar row).
    @State private var pendingForgetDevice: DeviceUi? = nil

    private var trustedDevices: [DeviceUi] {
        model.state.devices.filter { isTrusted($0) }
    }

    private var nearbyDevices: [DeviceUi] {
        model.state.devices.filter { !isTrusted($0) }
    }

    var body: some View {
        Group {
            #if os(iOS)
            if sizeClass == .compact {
                compactLayout
            } else {
                regularLayout
            }
            #else
            regularLayout
            #endif
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
        // Global rename sheet — presented from the iPad sidebar footer or any
        // other entry point that sets showRenameSheet = true.
        .sheet(isPresented: $showRenameSheet) {
            let currentName = {
                if let n = model.state.currentDeviceName, !n.isEmpty { return n }
                return model.state.systemDeviceName ?? ""
            }()
            RenameSheet(
                currentName: currentName,
                onDismiss: { showRenameSheet = false },
                onSave: { newName in
                    model.saveCustomDeviceName(newName.isEmpty ? nil : newName)
                    showRenameSheet = false
                }
            )
        }
        // Add-device picker: list of nearby untrusted candidates.
        .sheet(isPresented: $showAddDevicePicker) {
            AddDevicePickerSheet(
                candidates: nearbyDevices,
                onDismiss: { showAddDevicePicker = false },
                onPick: { device in
                    showAddDevicePicker = false
                    pendingLinkDevice = device
                }
            )
        }
        // Confirm dialog → starts the trust/pairing flow (same as Compose desktop).
        .sheet(item: $pendingLinkDevice, onDismiss: { pendingLinkDevice = nil }) { device in
            LinkDeviceConfirmDialog(
                device: device,
                onConfirm: {
                    model.addToTrusted(device)
                    pendingLinkDevice = nil
                },
                onDismiss: { pendingLinkDevice = nil }
            )
        }
        // Forget-device confirmation (from a trusted sidebar row's context menu).
        .sheet(item: $pendingForgetDevice, onDismiss: { pendingForgetDevice = nil }) { device in
            ForgetDeviceConfirmDialog(
                device: device,
                onConfirm: {
                    model.forgetDevice(device)
                    pendingForgetDevice = nil
                },
                onDismiss: { pendingForgetDevice = nil }
            )
        }
        // Auto-dismiss the picker once a device becomes trusted.
        .onChange(of: trustedDevices.count) { _, count in
            if count > 0 { showAddDevicePicker = false }
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
                    isOwned: isOwned,
                    deviceKind: deviceKind(for: route.deviceId)
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
                .navigationSplitViewColumnWidth(min: 200, ideal: 260)
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
        } detail: {
            if let route = model.selectedChat {
                let isOwned = isDeviceTrusted(deviceId: route.deviceId)
                DeviceChatScreen(
                    model: ChatModel(deviceId: route.deviceId, bootstrap: model.bootstrap),
                    deviceName: route.deviceName,
                    isOwned: isOwned,
                    deviceKind: deviceKind(for: route.deviceId)
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
    // Built inline: the system NavigationSplitView provides the column chrome,
    // and this view supplies the scrollable My-devices / Nearby sections (via
    // DeviceRowView) plus the local-device footer. (The standalone SidebarView
    // component is unused here — candidate for removal.)

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
                    // "My devices" section — the header carries a "+" to add a device.
                    SectionHeadView(label: "My devices") {
                        Button { showAddDevicePicker = true } label: {
                            Image(systemName: "plus")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundColor(kd.text2)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.horizontal, KdSpacing.s3)
                    .padding(.top, KdSpacing.s3)

                    if trusted.isEmpty {
                        addDevicePromptRow
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
                showRenameSheet = true
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
        .scrollContentBackground(.hidden)
    }

    // MARK: - Add-device prompt row (empty "My devices")

    /// Orange "+ Add a device" row shown when there are no trusted devices yet.
    /// Mirrors Compose WideLayout's AddDevicePromptRow.
    private var addDevicePromptRow: some View {
        Button { showAddDevicePicker = true } label: {
            HStack(spacing: KdSpacing.s2) {
                Image(systemName: "plus")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(kd.accent)
                Text("Add a device")
                    .kdStyle(.caption, color: kd.accent)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, KdSpacing.s5)
            .padding(.vertical, KdSpacing.s2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
        // Trusted devices can be un-trusted via a right-click / long-press menu.
        .contextMenu {
            if trusted {
                Button(role: .destructive) {
                    pendingForgetDevice = device
                } label: {
                    Label("Forget device", systemImage: "trash")
                }
            }
        }
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

    /// Avatar kind for the chat header — the discovered device's kind, or a generic
    /// phone if it's no longer in the list (better than the "unknown" placeholder).
    private func deviceKind(for deviceId: String) -> KdDeviceKind {
        model.state.devices.first(where: { $0.deviceId == deviceId })?.deviceKind ?? .iphone
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
