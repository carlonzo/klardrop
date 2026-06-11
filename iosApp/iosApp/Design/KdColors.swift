import SwiftUI

// ---------------------------------------------------------------------------
// KdColorScheme — typed token bag (SwiftUI translation of compose-ui KdColors.kt)
// Hex values verified against the Compose ARGB constants (0xFFRRGGBB -> 0xRRGGBB).
// ---------------------------------------------------------------------------

struct KdColorScheme {
    let bg0: Color
    let bg1: Color
    let bg2: Color
    let bg3: Color
    let border: Color
    let divider: Color

    let text: Color
    let text2: Color
    let text3: Color
    let textInv: Color

    let accent: Color
    let accentHi: Color
    let accentLo: Color
    /// Outbound bubble fill — accent with reduced alpha. Dark: 0.16, Light: 0.14
    let accentBg: Color

    let trust: Color
    let trustBg: Color
    let trustFg: Color

    let ok: Color
    let warn: Color
    let err: Color

    let bgSidebar: Color
}

// MARK: - Dark palette (default)

extension KdColorScheme {
    static let dark = KdColorScheme(
        bg0:       Color(hex: 0x181B20),
        bg1:       Color(hex: 0x1F2228),
        bg2:       Color(hex: 0x262A31),
        bg3:       Color(hex: 0x2F343C),
        border:    Color(hex: 0x393F49),
        divider:   Color(hex: 0x262A31),

        text:      Color(hex: 0xF5F3F0),
        text2:     Color(hex: 0xACB2B9),
        text3:     Color(hex: 0x6F757B),
        textInv:   Color(hex: 0x0E1217),

        accent:    Color(hex: 0xF39762),
        accentHi:  Color(hex: 0xFFAE7F),
        accentLo:  Color(hex: 0xCA723C),
        accentBg:  Color(hex: 0xF39762, alpha: 0.16),

        trust:     Color(hex: 0x7ECBB6),
        trustBg:   Color(hex: 0x7ECBB6, alpha: 0.18),
        trustFg:   Color(hex: 0x0A2A23),

        ok:        Color(hex: 0x77C87A),
        warn:      Color(hex: 0xE9B452),
        err:       Color(hex: 0xEE6A64),

        bgSidebar: Color(hex: 0x0E1115)
    )
}

// MARK: - Light palette

extension KdColorScheme {
    static let light = KdColorScheme(
        bg0:       Color(hex: 0xFCFAF6),
        bg1:       Color(hex: 0xF4F1ED),
        bg2:       Color(hex: 0xEEEBE5),
        bg3:       Color(hex: 0xE4E1DA),
        border:    Color(hex: 0xD7D4CD),
        divider:   Color(hex: 0xE7E4DF),

        text:      Color(hex: 0x13181D),
        text2:     Color(hex: 0x484E54),
        text3:     Color(hex: 0x757B81),
        textInv:   Color(hex: 0xF5F3F0),

        accent:    Color(hex: 0xF39762),
        accentHi:  Color(hex: 0xFFAE7F),
        accentLo:  Color(hex: 0xCA723C),
        accentBg:  Color(hex: 0xF39762, alpha: 0.14),   // light uses 0.14 per spec

        trust:     Color(hex: 0x7ECBB6),
        trustBg:   Color(hex: 0x7ECBB6, alpha: 0.18),
        trustFg:   Color(hex: 0x0A2A23),

        ok:        Color(hex: 0x77C87A),
        warn:      Color(hex: 0xE9B452),
        err:       Color(hex: 0xEE6A64),

        bgSidebar: Color(hex: 0xF4F1ED)   // light mode: sidebar == bg1
    )
}

// MARK: - Environment key

private struct KdColorsKey: EnvironmentKey {
    static let defaultValue: KdColorScheme = .dark
}

extension EnvironmentValues {
    var kdColors: KdColorScheme {
        get { self[KdColorsKey.self] }
        set { self[KdColorsKey.self] = newValue }
    }
}

// MARK: - View modifier

extension View {
    /// Injects the correct KdColorScheme for the active system color scheme.
    func kdColorsEnvironment() -> some View {
        modifier(KdColorsModifier())
    }
}

private struct KdColorsModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content.environment(\.kdColors, colorScheme == .dark ? .dark : .light)
    }
}
