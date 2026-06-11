import SwiftUI

// ---------------------------------------------------------------------------
// KdMotion — animation timing (SwiftUI translation of compose-ui KdMotion.kt)
//
// cubic-bezier(0.2, 0.7, 0.2, 1) -> Animation.timingCurve(0.2, 0.7, 0.2, 1)
// cubic-bezier(0.16, 1, 0.3, 1)  -> Animation.timingCurve(0.16, 1, 0.3, 1)
// ---------------------------------------------------------------------------

enum KdMotion {
    /// 140 ms — tap feedback, status-dot pulse, button hover
    static let durationFast: Double = 0.14
    /// 220 ms — sheet open, row insert/remove, transfer progress tick
    static let durationBase: Double = 0.22
    /// 360 ms — screen change, pairing accept, device promotion
    static let durationSlow: Double = 0.36

    /// KdEase: cubic-bezier(0.2, 0.7, 0.2, 1) — motion in/out
    static let ease: Animation = .timingCurve(0.2, 0.7, 0.2, 1, duration: durationBase)

    /// KdEaseOut: cubic-bezier(0.16, 1, 0.3, 1) — entrances (sheets, banners)
    static let easeOut: Animation = .timingCurve(0.16, 1, 0.3, 1, duration: durationBase)

    /// Fast ease (140ms) — micro-interactions
    static let fast: Animation = .timingCurve(0.2, 0.7, 0.2, 1, duration: durationFast)

    /// Slow ease (360ms) — screen transitions
    static let slow: Animation = .timingCurve(0.2, 0.7, 0.2, 1, duration: durationSlow)
}
