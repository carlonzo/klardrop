import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// PairingApprovalSheet — dialogs cluster
//
// Wraps PairingDialogView for an incoming pairing request, or renders an error
// card when state.isError. Presented from DiscoveryScreen / KlardropNav via
// .sheet(item: model.state.pairingDialogState).
//
// Mirrors TrustDialogs.kt PairingApprovalDialog.
// ---------------------------------------------------------------------------

struct PairingApprovalSheet: View {

    let state: PairingDialogState
    let onDismiss: () -> Void

    @Environment(\.kdColors) private var kd
    @State private var sheetHeight: CGFloat = 360

    var body: some View {
        content
        #if os(iOS)
        // Padding around the card, and a presentation detent that wraps the measured
        // content height (instead of the too-tall .medium).
        .padding(.horizontal, KdSpacing.s4)
        .padding(.vertical, KdSpacing.s3)
        .background(
            GeometryReader { proxy in
                Color.clear.preference(key: PairingSheetHeightKey.self, value: proxy.size.height)
            }
        )
        .onPreferenceChange(PairingSheetHeightKey.self) { h in
            if h > 0 { sheetHeight = h }
        }
        .presentationDetents([.height(sheetHeight)])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(KdRadii.sheet)
        .presentationBackground(kd.bg1)
        #else
        .padding(KdSpacing.s2)
        .frame(minWidth: 420, minHeight: 320)
        .background(kd.bg1)
        #endif
    }

    @ViewBuilder
    private var content: some View {
        if state.isError {
            errorContent
        } else {
            PairingDialogView(
                remoteDeviceName: state.deviceName,
                remoteKind: deviceKindFromString(state.deviceType),
                bodyText: "Accept this device into Your devices? You'll be able to send files and messages without prompting.",
                onCancel: onDismiss,
                onConfirm: state.onAccept
            )
        }
    }

    // MARK: - Error card

    private var errorContent: some View {
        VStack(alignment: .center, spacing: 0) {
            Spacer().frame(height: KdSpacing.s5)

            Text("Couldn't link device")
                .kdStyle(.headline, color: kd.text)
                .multilineTextAlignment(.center)

            Spacer().frame(height: KdSpacing.s3)

            Text(state.errorMessage ?? "Something went wrong while linking. Please try again.")
                .kdStyle(.body, color: kd.text2)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Spacer().frame(height: KdSpacing.s6)

            Button(action: onDismiss) {
                Text("Close")
                    .kdStyle(.body, color: kd.textInv)
                    .frame(maxWidth: .infinity)
                    .frame(height: KdSpacing.s8)
            }
            .buttonStyle(KdPrimaryButtonStyle())
            .padding(.horizontal, KdSpacing.s2)
        }
        .padding(KdSpacing.s6)
        .background(kd.bg2)
        .clipShape(KdShape.xl)
        .padding(.horizontal, KdSpacing.s4)
    }
}

// MARK: - DeviceType -> KdDeviceKind

/// Maps the Kotlin DeviceType string representation to a KdDeviceKind value.
/// DeviceType is a real Swift enum with .mobile / .desktop / .unknown cases.
private func deviceKindFromString(_ typeString: String) -> KdDeviceKind {
    switch typeString.uppercased() {
    case "MOBILE":  return .iphone
    case "DESKTOP": return .mac
    default:        return .unknown
    }
}

// MARK: - Sheet height measurement

/// Carries the pairing card's measured height up so the sheet detent can wrap it.
private struct PairingSheetHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}
