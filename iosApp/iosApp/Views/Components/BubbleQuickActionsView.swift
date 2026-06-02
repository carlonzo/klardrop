import SwiftUI

// ---------------------------------------------------------------------------
// BubbleQuickActionsView + QuickActionButton
// Subtle row of icon buttons rendered *below* a chat bubble, aligned to the
// speaker side.
// Mirrors: compose-ui/.../components/BubbleQuickActions.kt
// ---------------------------------------------------------------------------

struct BubbleQuickActionsView<Content: View>: View {
    let direction: KdBubbleDirection
    @ViewBuilder let content: () -> Content

    var body: some View {
        HStack(spacing: KdSpacing.s1) {
            if direction == .outgoing { Spacer(minLength: 0) }
            content()
            if direction == .incoming { Spacer(minLength: 0) }
        }
        .padding(.top, KdSpacing.s1)
        .padding(.horizontal, KdSpacing.s1)
    }
}

// MARK: - QuickActionButton

/// A 28pt pill-shaped icon button used in BubbleQuickActionsView.
struct QuickActionButton: View {
    let systemImage: String
    let accessibility: String
    let action: () -> Void

    @Environment(\.kdColors) private var kd

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .resizable()
                .scaledToFit()
                .frame(width: 16, height: 16)
                .foregroundColor(kd.text3)
                .frame(width: 28, height: 28)
                .background(kd.bg2.opacity(0.6))
                .clipShape(Capsule())
        }
        .accessibilityLabel(accessibility)
        .buttonStyle(.plain)
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s2) {
        BubbleView(text: "Check this out", direction: .incoming, timestamp: "10:32")
        BubbleQuickActionsView(direction: .incoming) {
            QuickActionButton(systemImage: "doc.on.doc", accessibility: "Copy") {}
            QuickActionButton(systemImage: "arrow.up.left.and.arrow.down.right", accessibility: "Expand") {}
        }

        BubbleView(text: "Sent!", direction: .outgoing, timestamp: "10:33")
        BubbleQuickActionsView(direction: .outgoing) {
            QuickActionButton(systemImage: "doc.on.doc", accessibility: "Copy") {}
        }
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
