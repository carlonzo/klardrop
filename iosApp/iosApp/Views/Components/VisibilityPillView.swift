import SwiftUI

// ---------------------------------------------------------------------------
// C04 · VisibilityPillView
// 32pt pill showing Wi-Fi broadcast visibility status.
// Mirrors: compose-ui/.../components/VisibilityPill.kt
// ---------------------------------------------------------------------------

/// Whether the device is broadcasting its presence on the local network.
enum KdVisibilityState {
    /// Device is advertising on the given Wi-Fi SSID.
    case visible(ssid: String)
    /// Visibility is off / not broadcasting.
    case hidden
}

struct VisibilityPillView: View {
    let state: KdVisibilityState
    var onTap: () -> Void = {}

    @Environment(\.kdColors) private var kd

    // MARK: - Derived tokens

    private var borderColor: Color {
        switch state {
        case .visible: return kd.border
        case .hidden:  return kd.err
        }
    }

    private var dotStatus: KdStatus {
        switch state {
        case .visible: return .ok
        case .hidden:  return .err
        }
    }

    private var label: String {
        switch state {
        case .visible(let ssid): return ssid
        case .hidden:            return "Hidden"
        }
    }

    // MARK: - Body

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: KdSpacing.s2) {
                StatusDotView(status: dotStatus, dotSize: 6)
                Text(label)
                    .kdStyle(.caption, color: kd.text2)
                    .lineLimit(1)
            }
            .padding(.horizontal, KdSpacing.s3)
            .padding(.vertical, KdSpacing.s2)
        }
        .buttonStyle(.plain)
        .frame(height: 32)
        .background(kd.bg1)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(borderColor, lineWidth: 1))
    }
}

// MARK: - Previews

#Preview {
    VStack(spacing: KdSpacing.s3) {
        VisibilityPillView(state: .visible(ssid: "Home Network"))
        VisibilityPillView(state: .hidden)
    }
    .padding()
    .background(Color(hex: 0x181B20))
    .kdColorsEnvironment()
}
