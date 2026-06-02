import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// DiscoveryView — Vertical slice (Phase 1B-1).
//
// Observes the real DiscoveryController.screenStateFlow via DiscoveryModel
// (live data, no mocks). Renders an empty state or a plain device list.
//
// iOS 14.1+ compatible:
//   - NavigationView instead of NavigationStack (iOS 16+)
//   - .onAppear Task instead of .task (iOS 15+)
//   - No .toolbarBackground (iOS 16+) — bg tinted via navigationViewStyle
//   - No .scrollContentBackground (iOS 16+) — workaround via UITableView
//   - No .listRowSeparatorTint (iOS 15+) — separator color set via appearance
//
// Intentionally minimal — full DeviceRow / chat / sheets come in 1B-2.
// ---------------------------------------------------------------------------

struct DiscoveryView: View {

    @StateObject private var model: DiscoveryModel
    @Environment(\.kdColors) private var kd

    init(bootstrap: KlardropBootstrap) {
        // StateObject wraps the model so it is created once per view lifetime.
        _model = StateObject(wrappedValue: DiscoveryModel(bootstrap: bootstrap))
    }

    var body: some View {
        NavigationView {
            ZStack {
                // Full-bleed background fills behind the list / empty state
                kd.bg0.ignoresSafeArea()

                if model.state.devices.isEmpty {
                    emptyState
                } else {
                    deviceList
                }
            }
            .navigationTitle("Klardrop")
        }
        .navigationViewStyle(.stack)
        // Lifecycle: start Kotlin StateFlow collection on appear, cancel on disappear.
        // .task{} is iOS 15+; use .onAppear with an explicit async Task instead.
        .onAppear { model.start() }
        .onDisappear { model.stop() }
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
            }
        }
        .listStyle(.plain)
        // iOS 16+ .scrollContentBackground(.hidden) not available; instead
        // suppress the default white UITableView background via appearance API
        // called once when this view first appears.
        .onAppear { configureListBackground() }
    }

    private func deviceRow(_ device: DeviceUi) -> some View {
        Button {
            model.onDeviceTap(device)
        } label: {
            HStack(spacing: KdSpacing.s3) {
                // Status dot (optional — shown only when meaningful)
                if let status = device.kdStatus {
                    StatusDotView(status: status)
                } else {
                    // Invisible placeholder to maintain leading alignment
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

// MARK: - iOS 14 List background workaround

/// On iOS 14/15, List has a hardcoded white background. Clear it via UITableView appearance.
private func configureListBackground() {
    UITableView.appearance().backgroundColor = .clear
    UITableViewCell.appearance().backgroundColor = .clear
}
