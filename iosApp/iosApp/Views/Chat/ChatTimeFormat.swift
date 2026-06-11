import Foundation

// ---------------------------------------------------------------------------
// ChatTimeFormat — native Swift reimplementation of the Kotlin expect fns.
// Mirrors: compose-ui/.../chat/ChatTimeFormat.kt (iOS actuals don't ship to Swift)
//
// Also owns:
//   - bytesFormatted(_:) — mirrors DeviceChatScreen.formatBytes
// ---------------------------------------------------------------------------

enum ChatTimeFormat {

    // MARK: - Date formatters (one instance per format, lazy statics)

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short   // locale-aware: "9:41 AM" or "21:41"
        return f
    }()

    private static let longFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium  // "Apr 17, 2026"
        f.timeStyle = .none
        return f
    }()

    // MARK: - Public API

    /// Short time string for a message timestamp, e.g. "9:41 AM" or "21:07".
    static func time(_ epochMillis: Int64) -> String {
        timeFormatter.string(from: date(epochMillis))
    }

    /// Day label for a date chip: "Today", "Yesterday", or "Apr 17, 2026".
    static func day(_ epochMillis: Int64) -> String {
        let cal = Calendar.current
        let d = date(epochMillis)
        if cal.isDateInToday(d)     { return "Today" }
        if cal.isDateInYesterday(d) { return "Yesterday" }
        return longFormatter.string(from: d)
    }

    /// Stable Int64 key representing the local calendar day (millis at midnight).
    /// Two timestamps on the same local calendar day return the same key.
    static func dayKey(_ epochMillis: Int64) -> Int64 {
        let cal = Calendar.current
        let comps = cal.dateComponents([.year, .month, .day], from: date(epochMillis))
        guard let midnight = cal.date(from: comps) else { return epochMillis }
        return Int64(midnight.timeIntervalSince1970 * 1000)
    }

    // MARK: - Bytes formatting (mirrors DeviceChatScreen.formatBytes)

    /// Human-readable file size string, e.g. "1.4 MB".
    static func bytesFormatted(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        let kb = Double(bytes) / 1024.0
        if kb < 1024 { return "\(oneDecimal(kb)) KB" }
        let mb = kb / 1024.0
        if mb < 1024 { return "\(oneDecimal(mb)) MB" }
        let gb = mb / 1024.0
        return "\(oneDecimal(gb)) GB"
    }

    // MARK: - Helpers

    private static func date(_ epochMillis: Int64) -> Date {
        Date(timeIntervalSince1970: Double(epochMillis) / 1000.0)
    }

    private static func oneDecimal(_ value: Double) -> String {
        let rounded = (value * 10).rounded() / 10
        let whole = Int(rounded)
        let frac = Int((rounded - Double(whole)) * 10)
        return frac == 0 ? "\(whole)" : "\(whole).\(frac)"
    }
}
