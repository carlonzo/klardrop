import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// AddDevicePickerSheet — dialogs cluster
//
// Bottom sheet (compact) / centered card (regular) listing nearby untrusted
// candidate devices as DeviceRowViews ("Tap to pair" / "Pairing…"). Empty
// hint shown when no candidates are nearby. Close button dismisses.
//
// Also provides AddDevicePlaceholderSurface — the empty-trusted-devices CTA
// card shown in DiscoveryScreen's "Your devices" section. Both live here so
// the add-device flow is entirely self-contained.
//
// Mirrors AddDevicePickerSheet.kt + AddDevicePlaceholderSurface.
// ---------------------------------------------------------------------------

struct AddDevicePickerSheet: View {

    let candidates: [DeviceUi]
    let onDismiss: () -> Void
    let onPick: (DeviceUi) -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        AddDevicePickerContent(
            candidates: candidates,
            onDismiss: onDismiss,
            onPick: onPick
        )
        #if os(iOS)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .frame(minWidth: 420, minHeight: 360)
        .background(kd.bg1)
        #endif
    }
}

// MARK: - Sheet content

private struct AddDevicePickerContent: View {

    let candidates: [DeviceUi]
    let onDismiss: () -> Void
    let onPick: (DeviceUi) -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            VStack(alignment: .leading, spacing: KdSpacing.s2) {
                Text("Add a device")
                    .kdStyle(.title, color: kd.text)
                    .padding(.horizontal, KdSpacing.s5)
                    .padding(.top, KdSpacing.s4)

                Text("Pick a nearby device to pair with. Trusted devices auto-share clipboard, Wi-Fi logins and notifications.")
                    .kdStyle(.caption, color: kd.text2)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, KdSpacing.s5)
                    .padding(.bottom, KdSpacing.s3)
            }

            Divider()
                .background(kd.divider)

            // Candidate list
            if candidates.isEmpty {
                emptyState
            } else {
                candidateList
            }

            Spacer(minLength: 0)

            // Close button
            HStack {
                Spacer()
                Button(action: onDismiss) {
                    Text("Close")
                        .kdStyle(.body, color: kd.accent)
                }
                .padding(.trailing, KdSpacing.s4)
                .padding(.bottom, KdSpacing.s4)
            }
        }
    }

    private var emptyState: some View {
        Text("No nearby devices right now. Make sure Klardrop is open on the device you want to pair, and that both are on the same Wi-Fi.")
            .kdStyle(.body, color: kd.text2)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(KdSpacing.s5)
    }

    private var candidateList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(candidates, id: \.deviceId) { device in
                    candidateRow(device)
                }
            }
        }
        .frame(maxHeight: KdSpacing.s7 * 11) // ~352pt, matches Compose heightIn(max)
    }

    @ViewBuilder
    private func candidateRow(_ device: DeviceUi) -> some View {
        let isPairing: Bool = {
            switch onEnum(of: device.trustStatus) {
            case .pairing: return true
            default: return false
            }
        }()

        DeviceRowView(
            name: device.deviceName,
            subText: isPairing ? "Pairing\u{2026}" : "Tap to pair",
            kind: device.deviceType.toKdDeviceKind(),
            avatarStyle: .neutral,
            rowState: isPairing ? .pairing : .idle,
            onTap: {
                if !isPairing { onPick(device) }
            }
        ) { EmptyView() }
        .padding(.horizontal, KdSpacing.s3)
    }
}

// MARK: - AddDevicePlaceholderSurface

/// Empty-trusted CTA card shown in DiscoveryScreen's "Your devices" section.
/// Tapping opens the AddDevicePickerSheet. Adapts layout to size class.
struct AddDevicePlaceholderSurface: View {

    let onClick: () -> Void

    @Environment(\.kdColors) private var kd
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    #endif

    var body: some View {
        Button(action: onClick) {
            #if os(iOS)
            let showHorizontal = sizeClass != .compact
            #else
            let showHorizontal = true
            #endif
            if showHorizontal {
                // Sidebar / regular: horizontal layout
                HStack(spacing: KdSpacing.s3) {
                    DeviceAvatarView(kind: .unknown, style: .neutral, size: KdSpacing.s7)

                    VStack(alignment: .leading, spacing: KdSpacing.s1) {
                        Text("Add a device")
                            .kdStyle(.body, color: kd.text)
                            .lineLimit(1)
                        Text("Pair from nearby devices")
                            .kdStyle(.caption, color: kd.text2)
                            .lineLimit(1)
                    }

                    Spacer()
                }
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s3)
            } else {
                // Compact: centred column
                VStack(spacing: KdSpacing.s2) {
                    DeviceAvatarView(kind: .unknown, style: .neutral, size: KdSpacing.s7)

                    Text("Add a device")
                        .kdStyle(.body, color: kd.text)
                        .lineLimit(1)

                    Text("Pair from nearby")
                        .kdStyle(.caption, color: kd.text2)
                        .lineLimit(1)
                }
                .padding(.vertical, KdSpacing.s3)
                .frame(maxWidth: .infinity)
            }
        }
        .buttonStyle(.plain)
        .background(kd.bg1)
        .clipShape(KdShape.lg)
    }
}
