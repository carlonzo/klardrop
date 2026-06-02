import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// DiscoveryView — Compact (iPhone) discovery screen — Phase 1B-2.
//
// Modernized idioms (iOS 17):
//   - @Observable DiscoveryAppModel (no ObservableObject / @StateObject)
//   - NavigationStack instead of NavigationView
//   - .scrollContentBackground(.hidden) instead of UITableView.appearance()
//   - .listRowBackground(Color.clear) + .listRowSeparator(.hidden)
//   - .task {} for lifecycle (auto-cancels on disappear; model.start is idempotent)
//
// The model is received as a plain `let` parameter — @Observable tracks reads
// automatically; no @ObservedObject / @Bindable needed for read-only access.
// ---------------------------------------------------------------------------

struct DiscoveryView: View {

    let model: DiscoveryAppModel
    let onNavigateToChat: (_ deviceId: String, _ deviceName: String) -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        ZStack {
            // Full-bleed background fills behind the list and safe areas.
            kd.bg0.ignoresSafeArea()

            if model.state.devices.isEmpty {
                emptyState
            } else {
                deviceList
            }
        }
        .navigationTitle("Klardrop")
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: KdSpacing.s4) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 48, weight: .light))
                .foregroundColor(kd.text3)

            Text("No devices nearby")
                .kdStyle(.body, color: kd.text2)

            Text("Make sure Klardrop is open on the same Wi-Fi.")
                .kdStyle(.caption, color: kd.text3, multiline: true)
                .multilineTextAlignment(.center)
                .padding(.horizontal, KdSpacing.s7)
        }
    }

    // MARK: - Device list

    private var deviceList: some View {
        List {
            ForEach(model.state.devices, id: \.deviceId) { device in
                deviceRow(device)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        // iOS 16+ native replacement for the UITableView.appearance() hack.
        .scrollContentBackground(.hidden)
    }

    private func deviceRow(_ device: DeviceUi) -> some View {
        Button {
            model.onDeviceTap(device)
            onNavigateToChat(device.deviceId, device.deviceName)
        } label: {
            HStack(spacing: KdSpacing.s3) {
                if let status = device.kdStatus {
                    StatusDotView(status: status)
                } else {
                    Circle()
                        .fill(Color.clear)
                        .frame(width: 16, height: 16)
                }

                Text(device.deviceName)
                    .kdStyle(.body, color: kd.text)
                    .lineLimit(1)

                Spacer()
            }
            .padding(.horizontal, KdSpacing.s4)
            .frame(minHeight: 56)
        }
        .buttonStyle(.plain)
    }
}
