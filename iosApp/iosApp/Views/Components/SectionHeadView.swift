import SwiftUI

// ---------------------------------------------------------------------------
// C05 · SectionHeadView
// Overline label + zero-padded mono count + optional trailing slot.
// Mirrors: compose-ui/.../components/SectionHead.kt
// ---------------------------------------------------------------------------

struct SectionHeadView<Trailing: View>: View {
    let label: String
    var count: Int? = nil
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.kdColors) private var kd

    var body: some View {
        HStack(spacing: KdSpacing.s2) {
            Text(label.uppercased())
                .kdStyle(.overline, color: kd.text3)

            if let c = count {
                Text(String(format: "%02d", c))
                    .kdStyle(.mono, color: kd.text3)
            }

            Spacer(minLength: 0)

            trailing()
        }
        .padding(.leading, KdSpacing.s5)
        .padding(.trailing, KdSpacing.s5)
        .padding(.top, KdSpacing.s5)
        .padding(.bottom, KdSpacing.s2)
    }
}

// MARK: - Convenience init (no trailing)

extension SectionHeadView where Trailing == EmptyView {
    init(label: String, count: Int? = nil) {
        self.label = label
        self.count = count
        self.trailing = { EmptyView() }
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: 0) {
        SectionHeadView(label: "My Devices", count: 3)
        SectionHeadView(label: "Nearby") {
            Button("Edit") {}
                .font(KdTypeRole.caption.font)
        }
    }
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
