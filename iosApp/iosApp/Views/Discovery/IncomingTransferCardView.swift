import SwiftUI

// ---------------------------------------------------------------------------
// C10 · IncomingTransferCardView
// Floating card when a stranger requests a transfer.
// Mirrors: compose-ui/.../components/IncomingTransferCard.kt
// ---------------------------------------------------------------------------

struct IncomingTransferCardView: View {
    let senderName: String
    var fileName: String? = nil
    var fileSize: String? = nil
    var subtitle: String = "wants to send you a file"
    let onAccept: () -> Void
    let onDecline: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Sender info row
            HStack(spacing: KdSpacing.s3) {
                DeviceAvatarView(kind: .unknown, style: .neutral, size: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text(senderName)
                        .kdStyle(.body, color: kd.text)
                        .lineLimit(1)
                    Text(subtitle)
                        .kdStyle(.caption, color: kd.text2)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(KdSpacing.s4)

            // File info (optional)
            if let fname = fileName {
                HStack(spacing: KdSpacing.s2) {
                    Text(fname)
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(kd.text)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let size = fileSize {
                        Text(size)
                            .kdStyle(.caption, color: kd.text3)
                    }
                }
                .padding(.horizontal, KdSpacing.s4)
                .padding(.bottom, KdSpacing.s3)
            }

            // Action buttons at 1 : 1.4 width ratio
            GeometryReader { geo in
                HStack(spacing: KdSpacing.s2) {
                    // Decline — ghost
                    Button(action: onDecline) {
                        Text("Decline")
                            .kdStyle(.body, color: kd.text)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .overlay(
                                RoundedRectangle(cornerRadius: KdRadii.md, style: .continuous)
                                    .strokeBorder(kd.border, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                    .frame(width: geo.size.width / 2.4)

                    // Accept — primary accent
                    Button(action: onAccept) {
                        Text("Accept")
                            .kdStyle(.body, color: kd.textInv)
                            .frame(maxWidth: .infinity)
                            .frame(height: 40)
                            .background(kd.accent)
                            .clipShape(RoundedRectangle(cornerRadius: KdRadii.md, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 40)
            .padding(.horizontal, KdSpacing.s4)
            .padding(.bottom, KdSpacing.s4)
        }
        .background(kd.bg2)
        .clipShape(KdShape.lg)
        .shadow(color: .black.opacity(0.12), radius: 4, x: 0, y: 2)
    }
}

#Preview {
    VStack(spacing: 16) {
        IncomingTransferCardView(
            senderName: "MacBook Pro",
            fileName: "presentation.pdf",
            fileSize: "2.4 MB",
            subtitle: "wants to send you a file",
            onAccept: {},
            onDecline: {}
        )
        IncomingTransferCardView(
            senderName: "Unknown Device",
            subtitle: "wants to send you a message",
            onAccept: {},
            onDecline: {}
        )
    }
    .padding(16)
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
