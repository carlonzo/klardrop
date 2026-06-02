import CoreGraphics

// ---------------------------------------------------------------------------
// KdSpacing — 4-pt grid scale (SwiftUI translation of compose-ui KdSpacing.kt)
// Values in points (1pt = 1dp on iOS at 1x scale).
// ---------------------------------------------------------------------------

enum KdSpacing {
    static let s1: CGFloat = 4
    static let s2: CGFloat = 8
    static let s3: CGFloat = 12
    static let s4: CGFloat = 16
    static let s5: CGFloat = 20
    static let s6: CGFloat = 24
    static let s7: CGFloat = 32
    static let s8: CGFloat = 40
    static let s9: CGFloat = 48
    /// S03 inter-element gap
    static let gap: CGFloat = 14
    /// C01 84-pt avatar (chat empty state, single-device pair)
    static let heroAvatar: CGFloat = 84
}
