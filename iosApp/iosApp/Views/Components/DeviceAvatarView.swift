import SwiftUI

// ---------------------------------------------------------------------------
// C01 · DeviceAvatarView
// Circular avatar with SF Symbol glyph, optional StatusDotView overlay.
// Mirrors: compose-ui/.../components/DeviceAvatar.kt
// ---------------------------------------------------------------------------

/// Platform kind — used to pick an SF Symbol glyph for the avatar.
/// Owned here; reused by DeviceRowView and other device-list cluster views.
enum KdDeviceKind {
    case mac
    case iphone
    case android
    case pc
    case tablet
    case unknown
}

/// Avatar tint style.
/// Tinted = accent-coloured fill (trusted devices).
/// Neutral = bg2 fill (nearby / unknown devices).
enum KdAvatarStyle {
    case tinted
    case neutral
}

struct DeviceAvatarView: View {
    let kind: KdDeviceKind
    var style: KdAvatarStyle = .neutral
    var status: KdStatus? = nil
    var size: CGFloat = 48

    @Environment(\.kdColors) private var kd

    private var symbolName: String {
        switch kind {
        case .mac:     return "laptopcomputer"
        case .iphone:  return "iphone"
        case .android: return "iphone"          // No Android glyph in SF Symbols; use iphone shape
        case .pc:      return "desktopcomputer"
        case .tablet:  return "ipad"
        case .unknown: return "questionmark.square.dashed"
        }
    }

    private var bgColor: Color {
        switch style {
        case .tinted:  return kd.trustBg
        case .neutral: return kd.bg2
        }
    }

    private var fgColor: Color {
        switch style {
        case .tinted:  return kd.trust
        case .neutral: return kd.text2
        }
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ZStack {
                Circle()
                    .fill(bgColor)
                    .frame(width: size, height: size)
                Image(systemName: symbolName)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size * 0.42, height: size * 0.42)
                    .foregroundColor(fgColor)
            }

            if let s = status {
                StatusDotView(status: s, dotSize: max(6, size * 0.175))
                    .offset(x: 1, y: 1)
            }
        }
        .frame(width: size, height: size)
    }
}

#Preview {
    HStack(spacing: 16) {
        DeviceAvatarView(kind: .mac, style: .tinted, status: .ok, size: 48)
        DeviceAvatarView(kind: .iphone, style: .neutral, status: .warn, size: 48)
        DeviceAvatarView(kind: .unknown, style: .neutral, size: 36)
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
