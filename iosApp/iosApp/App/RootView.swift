import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// RootView — App root — Phase 1B-2.
//
// Creates the single DiscoveryAppModel (via @State so it is created once per
// view lifetime and survives re-renders), applies the kd.bg0 background, and
// drives model.start()/.stop() via .task (auto-cancels on disappear).
//
// KlardropNav is the size-class router inside; RootView is just the lifecycle
// and environment wrapper.
//
// Also the entry point for the Share Extension hand-off: an incoming
// klardrop://share URL drains the App Group inbox, bridges the dropped files to
// PlatformFile, and presents ShareInboxSheet to pick a destination device.
// ---------------------------------------------------------------------------

struct RootView: View {

    let bootstrap: KlardropBootstrap
    @Environment(\.kdColors) private var kd

    // @State on an @Observable class: created exactly once per view lifetime.
    // @MainActor init is called synchronously on the main thread.
    @State private var model: DiscoveryAppModel

    // Share-extension hand-off state.
    @State private var shareFiles: [Filekit_corePlatformFile] = []
    @State private var showShareInbox = false

    init(bootstrap: KlardropBootstrap) {
        self.bootstrap = bootstrap
        // Initialize @State with a DiscoveryAppModel seeded from bootstrap.
        // This is safe: @State stores are initialized before `body` is first called.
        _model = State(initialValue: DiscoveryAppModel(bootstrap: bootstrap))
    }

    var body: some View {
        KlardropNav(model: model)
            .background(kd.bg0.ignoresSafeArea())
            // .task auto-cancels on disappear, so stop() must also call onDispose
            // semantics. model.start() is idempotent (guards stateTasks.isEmpty).
            .task {
                model.start()
            }
            .onDisappear {
                model.stop()
            }
            .onOpenURL { url in
                handleShareURL(url)
            }
            .sheet(isPresented: $showShareInbox) {
                ShareInboxSheet(model: model, files: shareFiles) {
                    showShareInbox = false
                    shareFiles = []
                }
            }
    }

    // MARK: - Share-extension hand-off

    private func handleShareURL(_ url: URL) {
        guard url.scheme == ShareInbox.urlScheme, url.host == ShareInbox.urlHost else { return }

        // Drain the App Group inbox and re-home each file into our own temp
        // sandbox (mirrors FilePicking: Kotlin reads them asynchronously off the
        // main thread, so they must outlive this call and not depend on the
        // shared container). The shared-container copies are deleted right away.
        let paths = ShareInbox.drainPendingPaths()
        var files: [Filekit_corePlatformFile] = []
        for path in paths {
            if let local = copyIntoTemp(path) {
                files.append(PlatformFileBridgeKt.platformFileFromPath(path: local))
            }
            try? FileManager.default.removeItem(atPath: path)
        }

        guard !files.isEmpty else { return }
        shareFiles = files
        showShareInbox = true
    }

    private func copyIntoTemp(_ path: String) -> String? {
        let src = URL(fileURLWithPath: path)
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let dest = dir.appendingPathComponent(src.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try FileManager.default.copyItem(at: src, to: dest)
            return dest.path
        } catch {
            return nil
        }
    }
}
