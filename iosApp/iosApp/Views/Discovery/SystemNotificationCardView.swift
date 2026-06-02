import SwiftUI

// ---------------------------------------------------------------------------
// C15 · SystemNotificationCardView
// Slim notification card (bg2, rounded, dual-action footer 1:1.4).
// Mirrors: compose-ui/.../components/SystemNotificationCard.kt
// ---------------------------------------------------------------------------

struct SystemNotificationCardView: View {
    let title: String
    let bodyText: String
    let primaryAction: String
    let onPrimary: () -> Void
    let secondaryAction: String
    let onSecondary: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header text
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .kdStyle(.body, color: kd.text)
                Text(bodyText)
                    .kdStyle(.caption, color: kd.text2)
            }
            .padding(KdSpacing.s4)
            .frame(maxWidth: .infinity, alignment: .leading)

            Spacer().frame(height: KdSpacing.s1)

            // Action row — Dismiss (ghost, weight 1) : Primary (accent, weight 1.4)
            GeometryReader { geo in
                HStack(spacing: KdSpacing.s2) {
                    // Secondary / Dismiss
                    Button(action: onSecondary) {
                        Text(secondaryAction)
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

                    // Primary action
                    Button(action: onPrimary) {
                        Text(primaryAction)
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
    SystemNotificationCardView(
        title: "Carlo's iPhone",
        bodyText: "removed this device. You're no longer paired.",
        primaryAction: "Pair",
        onPrimary: {},
        secondaryAction: "Dismiss",
        onSecondary: {}
    )
    .padding(16)
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
