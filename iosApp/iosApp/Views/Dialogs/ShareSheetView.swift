import SwiftUI

// ---------------------------------------------------------------------------
// ShareSheetView — C11 · dialogs cluster
//
// Bottom-sheet share UI: horizontal trusted tiles + nearby DeviceRows +
// primary 'Send to X' CTA. The struct KdShareDevice is defined here per the
// architect contract (owned by leaf/dialogs). On iOS this is presented via
// .sheet with presentationDetents; onSend triggers the native file picker
// then model.onSendData(.FilesList).
//
// Mirrors components/ShareSheet.kt.
// ---------------------------------------------------------------------------

/// A single device entry in the share sheet.
/// Mirrors the Compose KdShareDevice data class.
struct KdShareDevice: Identifiable {
    let id: String
    let name: String
    let kind: KdDeviceKind
    let isTrusted: Bool
    var status: KdStatus? = nil
}

struct ShareSheetView: View {

    let trustedDevices: [KdShareDevice]
    let nearbyDevices: [KdShareDevice]
    @Binding var selectedId: String?
    var onSelect: (KdShareDevice) -> Void = { _ in }
    let onSend: (KdShareDevice?) -> Void

    @Environment(\.kdColors) private var kd

    private var allDevices: [KdShareDevice] { trustedDevices + nearbyDevices }
    private var selectedDevice: KdShareDevice? {
        allDevices.first { $0.id == selectedId }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Drag handle
            Capsule()
                .fill(kd.text3.opacity(0.40))
                .frame(width: 40, height: 4)
                .frame(maxWidth: .infinity)
                .padding(.top, KdSpacing.s2)

            Spacer().frame(height: KdSpacing.s4)

            // "Your Devices" horizontal scroll
            if !trustedDevices.isEmpty {
                SectionHeadView(label: "Your Devices", count: trustedDevices.count) { EmptyView() }
                    .padding(.horizontal, KdSpacing.s4)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: KdSpacing.s3) {
                        ForEach(trustedDevices) { device in
                            TrustedDeviceTileView(
                                device: device,
                                isSelected: device.id == selectedId,
                                onTap: { onSelect(device); selectedId = device.id }
                            )
                        }
                    }
                    .padding(.horizontal, KdSpacing.s4)
                }
                .padding(.vertical, KdSpacing.s2)

                Spacer().frame(height: KdSpacing.s4)
            }

            // "Nearby" list
            if !nearbyDevices.isEmpty {
                SectionHeadView(label: "Nearby", count: nearbyDevices.count) { EmptyView() }
                    .padding(.horizontal, KdSpacing.s4)

                VStack(spacing: 0) {
                    ForEach(nearbyDevices) { device in
                        DeviceRowView(
                            name: device.name,
                            subText: "1-time send \u{00B7} accept on receiver",
                            kind: device.kind,
                            avatarStyle: .neutral,
                            rowState: device.id == selectedId ? .active : .idle,
                            status: device.status,
                            onTap: { onSelect(device); selectedId = device.id }
                        )
                        .padding(.horizontal, KdSpacing.s3)
                    }
                }

                Spacer().frame(height: KdSpacing.s4)
            }

            // Primary CTA
            Button(action: { onSend(selectedDevice) }) {
                Text(selectedDevice != nil
                     ? "Send to \(selectedDevice!.name)"
                     : "Select a device")
                    .kdStyle(.body, color: selectedDevice != nil ? kd.textInv : kd.text3)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
            }
            .disabled(selectedDevice == nil)
            .background(selectedDevice != nil ? kd.accent : kd.bg3)
            .clipShape(KdShape.md)
            .padding(.horizontal, KdSpacing.s4)
            .animation(.easeInOut(duration: 0.15), value: selectedId)

            Spacer().frame(height: KdSpacing.s7)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden) // hand-drawn above
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
    }
}

// MARK: - Trusted device tile (horizontal scroll item)

private struct TrustedDeviceTileView: View {

    let device: KdShareDevice
    let isSelected: Bool
    let onTap: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: KdSpacing.s2) {
                ZStack {
                    // Selection ring
                    if isSelected {
                        Circle()
                            .stroke(kd.trust, lineWidth: 2)
                            .frame(width: 52, height: 52)
                    }
                    DeviceAvatarView(
                        kind: device.kind,
                        style: .tinted,
                        status: device.status,
                        size: 48
                    )
                    .padding(isSelected ? 2 : 0)
                }

                Text(device.name)
                    .kdStyle(.caption, color: kd.text)
                    .lineLimit(1)
                    .frame(width: 80)
                    .multilineTextAlignment(.center)
            }
            .frame(width: 92)
        }
        .buttonStyle(.plain)
    }
}
