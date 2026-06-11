import SwiftUI

// ---------------------------------------------------------------------------
// PairingDialogView — C12 · dialogs cluster
//
// Modal pairing confirmation content: device avatar + title + body + Cancel /
// Accept button pair (1:1.4 weight split). Mirrors components/PairingDialog.kt
// (simple path — the verification-code two-avatar variant is reserved for
// future protocol upgrades and omitted here per the architect plan).
//
// Caller presents this inside a .sheet (compact) or a centered card overlay
// (regular). KdDeviceKind and KdAvatarStyle are owned by the leaf cluster and
// referenced here by name (they'll exist after all clusters run).
// ---------------------------------------------------------------------------

struct PairingDialogView: View {

    let remoteDeviceName: String
    var remoteKind: KdDeviceKind = .unknown
    /// Optional body text for the pairing dialog. Named `bodyText` to avoid shadowing
    /// SwiftUI's required `var body: some View` computed property.
    var bodyText: String? = nil
    var confirmLabel: String? = nil
    var cancelLabel: String = "Cancel"
    let onCancel: () -> Void
    let onConfirm: () -> Void

    @Environment(\.kdColors) private var kd

    private var resolvedBody: String {
        bodyText ?? "Accept this device into Your devices? You'll be able to send files and messages without prompting."
    }

    private var resolvedConfirm: String {
        confirmLabel ?? "Accept"
    }

    var body: some View {
        VStack(alignment: .center, spacing: 0) {
            // Avatar
            DeviceAvatarView(kind: remoteKind, style: .neutral, size: KdSpacing.heroAvatar)

            Spacer().frame(height: KdSpacing.s5)

            // Title
            Text("Pair with \(remoteDeviceName)?")
                .kdStyle(.headline, color: kd.text)
                .multilineTextAlignment(.center)

            Spacer().frame(height: KdSpacing.s2)

            // Body
            Text(resolvedBody)
                .kdStyle(.body, color: kd.text2)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            Spacer().frame(height: KdSpacing.s6)

            // Button row: Cancel (flex 1) | Confirm (flex 1.4).
            // Each button's flex space is achieved via GeometryReader weights.
            GeometryReader { geo in
                let gap      = KdSpacing.s2
                let total    = geo.size.width - gap
                let cancelW  = total * (1.0 / 2.4)
                let confirmW = total * (1.4 / 2.4)

                HStack(spacing: gap) {
                    Button(action: onCancel) {
                        Text(cancelLabel)
                            .kdStyle(.body, color: kd.text)
                            .frame(width: cancelW, height: KdSpacing.s8)
                    }
                    .buttonStyle(.bordered)
                    .clipShape(KdShape.md)

                    Button(action: onConfirm) {
                        Text(resolvedConfirm)
                            .kdStyle(.body, color: kd.textInv)
                            .frame(width: confirmW, height: KdSpacing.s8)
                    }
                    .buttonStyle(KdPrimaryButtonStyle())
                }
            }
            .frame(height: KdSpacing.s8)
        }
        .padding(KdSpacing.s6)
        .background(kd.bg2)
        .clipShape(KdShape.xl)
    }
}

// MARK: - Primary button style (accent fill, radius md)

struct KdPrimaryButtonStyle: ButtonStyle {
    @Environment(\.kdColors) private var kd

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(kd.accent.opacity(configuration.isPressed ? 0.80 : 1.0))
            .clipShape(KdShape.md)
            .animation(.easeInOut(duration: 0.12), value: configuration.isPressed)
    }
}
