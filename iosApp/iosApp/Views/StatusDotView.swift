import SwiftUI

// ---------------------------------------------------------------------------
// StatusDotView — 8pt filled status indicator with a soft halo.
// Warn variant pulses opacity (220ms, reverse, repeatForever) per spec.
//
// Usage:
//   StatusDotView(status: .ok)
//   StatusDotView(status: .warn, dotSize: 10)
// ---------------------------------------------------------------------------

/// Semantic status for the status dot indicator.
enum KdStatus {
    case ok     // green  (reachable)
    case warn   // amber  (probing / pairing)
    case err    // red    (unreachable)
}

struct StatusDotView: View {
    let status: KdStatus
    var dotSize: CGFloat = 8

    @Environment(\.kdColors) private var kd
    @State private var pulsing = false

    private var dotColor: Color {
        switch status {
        case .ok:   return kd.ok
        case .warn: return kd.warn
        case .err:  return kd.err
        }
    }

    var body: some View {
        ZStack {
            // Soft halo ring at 18% opacity
            Circle()
                .fill(dotColor.opacity(0.18))
                .frame(width: dotSize * 2, height: dotSize * 2)
            // Filled core dot
            Circle()
                .fill(dotColor)
                .frame(width: dotSize, height: dotSize)
                .opacity(status == .warn ? (pulsing ? 0.45 : 1.0) : 1.0)
        }
        .onAppear {
            guard status == .warn else { return }
            withAnimation(KdMotion.ease.repeatForever(autoreverses: true)) {
                pulsing = true
            }
        }
        .onDisappear {
            pulsing = false
        }
    }
}
