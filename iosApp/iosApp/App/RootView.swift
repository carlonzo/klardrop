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
// ---------------------------------------------------------------------------

struct RootView: View {

    let bootstrap: KlardropBootstrap
    @Environment(\.kdColors) private var kd

    // @State on an @Observable class: created exactly once per view lifetime.
    // @MainActor init is called synchronously on the main thread.
    @State private var model: DiscoveryAppModel

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
    }
}
