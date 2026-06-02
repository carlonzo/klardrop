import SwiftUI

// ---------------------------------------------------------------------------
// KdType — Typography roles (SwiftUI translation of compose-ui KdTypography.kt)
// iOS 17+ — .kerning() and .tracking() are unconditionally available.
//
// Manrope (design spec) -> SF (system sans-serif, .default design)
// JetBrains Mono        -> system monospaced (.monospaced design)
// ---------------------------------------------------------------------------

enum KdTypeRole {
    case display    // 700, 28pt/32, kerning -0.56pt
    case title      // 700, 20pt/26, kerning -0.20pt
    case headline   // 600, 17pt/22
    case body       // 500, 15pt/21
    case caption    // 500, 13pt/17
    case overline   // 600, 11pt/14, kerning +1.1pt, uppercase
    case mono       // mono 500, 12pt/16
}

extension KdTypeRole {
    var font: Font {
        switch self {
        case .display:
            return .system(size: 28, weight: .bold)
        case .title:
            return .system(size: 20, weight: .bold)
        case .headline:
            return .system(size: 17, weight: .semibold)
        case .body:
            return .system(size: 15, weight: .medium)
        case .caption:
            return .system(size: 13, weight: .medium)
        case .overline:
            return .system(size: 11, weight: .semibold)
        case .mono:
            return .system(size: 12, weight: .medium, design: .monospaced)
        }
    }

    /// Letter-spacing in points applied via .kerning().
    var kerningValue: CGFloat {
        switch self {
        case .display:   return -0.56
        case .title:     return -0.20
        case .overline:  return  1.10
        default:         return  0
        }
    }

    /// Additional line spacing in points (lineHeight - fontSize) for multi-line text.
    var lineSpacing: CGFloat {
        switch self {
        case .display:   return 32 - 28  //  4pt
        case .title:     return 26 - 20  //  6pt
        case .headline:  return 22 - 17  //  5pt
        case .body:      return 21 - 15  //  6pt
        case .caption:   return 17 - 13  //  4pt
        case .overline:  return 14 - 11  //  3pt
        case .mono:      return 16 - 12  //  4pt
        }
    }
}

// MARK: - View / Text modifiers

extension View {
    /// Apply KdType font + foregroundColor + kerning in one call.
    /// Use the `multiline` parameter when the view may wrap to get correct line height.
    func kdStyle(_ role: KdTypeRole, color: Color? = nil, multiline: Bool = false) -> some View {
        self
            .font(role.font)
            .kerning(role.kerningValue)
            .lineSpacing(multiline ? role.lineSpacing : 0)
            .modify { view in
                if let color {
                    view.foregroundColor(color)
                } else {
                    view
                }
            }
    }
}

// MARK: - Conditional chaining helper

extension View {
    @ViewBuilder
    fileprivate func modify<T: View>(@ViewBuilder _ transform: (Self) -> T) -> some View {
        transform(self)
    }
}
