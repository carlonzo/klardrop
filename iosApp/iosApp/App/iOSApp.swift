import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// iOSApp — App entry point (Phase 1B-1 rewrite, replacing Compose bridge).
//
// Owns ONE KlardropBootstrap for the entire app lifetime. The bootstrap
// constructs the Klardrop graph (including CommonComponent, all services, and
// the discovery/server singletons). Passing it as a reference into child views
// ensures all screens share the same controller instances.
//
// Crash reporting is started by KlardropBootstrap on the Kotlin side, so there is
// no SDK import here any more.
// ---------------------------------------------------------------------------

@main
struct iOSApp: App {

    private let bootstrap: KlardropBootstrap

    init() {
        // Construct the single KMP graph. Done once; held for process lifetime.
        // This also starts Sentry (see KlardropBootstrap).
        bootstrap = KlardropBootstrap()
    }

    var body: some Scene {
        WindowGroup {
            RootView(bootstrap: bootstrap)
                // Propagate the correct KdColorScheme for the system color scheme.
                .kdColorsEnvironment()
                // Follow system; KdColors provides both dark and light palettes.
                .preferredColorScheme(nil)
        }
    }
}

