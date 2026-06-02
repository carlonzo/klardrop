import SwiftUI

// ---------------------------------------------------------------------------
// C15 · DateChipView
// Centered date pill separating chat thread days.
// Mirrors: compose-ui/.../components/DateChip.kt
// ---------------------------------------------------------------------------

struct DateChipView: View {
    let label: String

    @Environment(\.kdColors) private var kd

    var body: some View {
        HStack {
            Spacer(minLength: 0)
            Text(label)
                .kdStyle(.overline, color: kd.text3)
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s1)
                .background(kd.bg1)
                .clipShape(Capsule())
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s3) {
        DateChipView(label: "TODAY")
        DateChipView(label: "YESTERDAY")
        DateChipView(label: "MAY 5, 2026")
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
