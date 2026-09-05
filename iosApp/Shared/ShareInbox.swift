import Foundation
import UniformTypeIdentifiers

// ---------------------------------------------------------------------------
// ShareInbox — the contract between the Share Extensions and the host app.
//
// Compiled into ALL FOUR targets (iosApp, KlardropMac, KlardropShare,
// KlardropMacShare). The extensions copy received attachments into the shared
// App Group container and record their paths; the host app drains those paths
// on the next `klardrop://share` open and feeds them into the send UI.
//
// App Group identifier differs by platform: macOS requires the Team ID prefix,
// iOS does not. Each target compiles for exactly one platform, so the #if picks
// the right value automatically.
// ---------------------------------------------------------------------------

enum ShareInbox {

    /// App Group identifier. macOS sandbox requires the Team ID prefix.
    static var appGroupID: String {
        #if os(macOS)
        return "D7T5425WSW.group.com.carlom.Klardrop"
        #else
        return "group.com.carlom.Klardrop"
        #endif
    }

    static let pendingPathsKey = "pendingSharePaths"
    static let inboxDirName = "ShareInbox"
    static let urlScheme = "klardrop"
    static let urlHost = "share"

    /// Directory inside the App Group container where the extension drops files.
    static func inboxDirectory() -> URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent(inboxDirName, isDirectory: true)
    }

    // MARK: - Extension side

    /// Copy a received file URL into the shared inbox. Returns the destination
    /// path on success. The UUID prefix avoids collisions across share actions.
    static func ingest(_ src: URL) -> String? {
        guard let dir = inboxDirectory() else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = src.lastPathComponent.isEmpty ? "shared_file" : src.lastPathComponent
        let dest = dir.appendingPathComponent(UUID().uuidString + "_" + name)

        let accessing = src.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                src.stopAccessingSecurityScopedResource()
            }
        }

        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try? FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: src, to: dest)
            return dest.path
        } catch {
            do {
                let data = try Data(contentsOf: src)
                try data.write(to: dest)
                return dest.path
            } catch {
                return nil
            }
        }
    }

    /// Save raw data into the shared inbox. Returns the destination path on success.
    static func ingest(data: Data, suggestedType: String) -> String? {
        guard let dir = inboxDirectory(), !data.isEmpty else { return nil }
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = UTType(suggestedType)?.preferredFilenameExtension ?? "bin"
        let dest = dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
        do {
            try data.write(to: dest)
            return dest.path
        } catch {
            return nil
        }
    }

    /// Record the dropped paths and return the URL that opens the host app.
    /// Returns nil when there is nothing to share.
    static func publish(paths: [String]) -> URL? {
        guard !paths.isEmpty else { return nil }
        let defaults = UserDefaults(suiteName: appGroupID)
        defaults?.set(paths, forKey: pendingPathsKey)
        var comps = URLComponents()
        comps.scheme = urlScheme
        comps.host = urlHost
        return comps.url
    }

    // MARK: - Host side

    /// Return the pending shared paths and clear them so they fire only once.
    static func drainPendingPaths() -> [String] {
        let defaults = UserDefaults(suiteName: appGroupID)
        let paths = defaults?.stringArray(forKey: pendingPathsKey) ?? []
        defaults?.removeObject(forKey: pendingPathsKey)
        return paths
    }
}
