import SwiftUI

// ---------------------------------------------------------------------------
// C08 · BannerView
// Inline ok / warn / err status banner.
// Mirrors: compose-ui/.../components/Banner.kt
// ---------------------------------------------------------------------------

/// Color tone for BannerView — matches Kotlin KdBannerTone.
enum KdBannerTone {
    case ok
    case warn
    case err
}

struct BannerView<Trailing: View>: View {
    let tone: KdBannerTone
    let title: String
    var bodyText: String? = nil
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.kdColors) private var kd

    // MARK: - Derived tokens

    private var toneColor: Color {
        switch tone {
        case .ok:   return kd.ok
        case .warn: return kd.warn
        case .err:  return kd.err
        }
    }

    private var bgColor: Color {
        toneColor.opacity(0.10)
    }

    private var systemImage: String {
        switch tone {
        case .ok:   return "checkmark.circle.fill"
        case .warn: return "exclamationmark.triangle.fill"
        case .err:  return "exclamationmark.octagon.fill"
        }
    }

    // MARK: - View body

    var body: some View {
        HStack(spacing: KdSpacing.s3) {
            // 28pt icon tile
            ZStack {
                Circle()
                    .fill(kd.text.opacity(0.04))
                    .frame(width: 28, height: 28)
                Image(systemName: systemImage)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 16, height: 16)
                    .foregroundColor(toneColor)
            }

            // Title + optional body text
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .kdStyle(.body, color: kd.text)
                if let bt = bodyText {
                    Text(bt)
                        .kdStyle(.caption, color: kd.text2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Optional trailing slot
            trailing()
        }
        .padding(.horizontal, KdSpacing.s3)
        .padding(.vertical, 10)
        .background(bgColor)
        .clipShape(KdShape.md)
    }
}

// MARK: - Convenience init (no trailing)

extension BannerView where Trailing == EmptyView {
    init(tone: KdBannerTone, title: String, body: String? = nil) {
        self.tone = tone
        self.title = title
        self.bodyText = body
        self.trailing = { EmptyView() }
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s3) {
        BannerView(tone: .ok, title: "Connected", body: "Sharing over Wi-Fi")
        BannerView(tone: .warn, title: "Pairing in progress")
        BannerView(tone: .err, title: "Unreachable", bodyText: "Device not found on network") {
            Button("Retry") {}
                .font(KdTypeRole.caption.font)
        }
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
