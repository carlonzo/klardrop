import SwiftUI

extension Color {
    /// Create a Color from a 24-bit hex integer (e.g. 0xFF5733) with optional alpha.
    /// Uses sRGB color space to match the Compose ARGB hex values.
    init(hex: UInt32, alpha: Double = 1.0) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(.sRGB, red: r, green: g, blue: b, opacity: alpha)
    }
}
