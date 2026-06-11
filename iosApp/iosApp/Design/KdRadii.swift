import SwiftUI

// ---------------------------------------------------------------------------
// KdRadii — corner radius tokens (SwiftUI translation of compose-ui KdRadii.kt)
// iOS 14.1+ compatible.
// ---------------------------------------------------------------------------

enum KdRadii {
    /// 6 pt — status dot outline, chip tints
    static let xs: CGFloat = 6
    /// 10 pt — file icon tile inside a file card
    static let sm: CGFloat = 10
    /// 14 pt — buttons, banners, selected state
    static let md: CGFloat = 14
    /// 18 pt — device row, chat bubble, file card
    static let lg: CGFloat = 18
    /// 24 pt — dialog, hero card
    static let xl: CGFloat = 24
    /// 28 pt — bottom sheet top corner
    static let sheet: CGFloat = 28
}

// MARK: - Convenience shapes

enum KdShape {
    static let xs = RoundedRectangle(cornerRadius: KdRadii.xs, style: .continuous)
    static let sm = RoundedRectangle(cornerRadius: KdRadii.sm, style: .continuous)
    static let md = RoundedRectangle(cornerRadius: KdRadii.md, style: .continuous)
    static let lg = RoundedRectangle(cornerRadius: KdRadii.lg, style: .continuous)
    static let xl = RoundedRectangle(cornerRadius: KdRadii.xl, style: .continuous)
    /// Sheet: only top corners rounded (iOS 14 compatible — uses Path-based shape)
    static let sheet = TopRoundedRectangle(radius: KdRadii.sheet)
    /// Pill / fully-rounded capsule
    static let pill = Capsule()
}

// MARK: - TopRoundedRectangle (iOS 14+ compatible, top corners only)

struct TopRoundedRectangle: Shape {
    let radius: CGFloat
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.minX + radius, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + radius),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + radius))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + radius, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        path.closeSubpath()
        return path
    }
}
