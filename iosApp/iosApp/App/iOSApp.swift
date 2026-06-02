import SwiftUI
import presentation
import Bugsnag

// ---------------------------------------------------------------------------
// iOSApp — App entry point (Phase 1B-1 rewrite, replacing Compose bridge).
//
// Owns ONE KlardropBootstrap for the entire app lifetime. The bootstrap
// constructs the Klardrop graph (including CommonComponent, all services, and
// the discovery/server singletons). Passing it as a reference into child views
// ensures all screens share the same controller instances.
//
// Bugsnag is initialized here exactly as before; only the Compose bridge is
// removed. import common_ui and ComposeView are gone.
// ---------------------------------------------------------------------------

@main
struct iOSApp: App {

    private let bootstrap: KlardropBootstrap

    init() {
        // Keep the existing Bugsnag setup unchanged.
        let config = BugsnagConfig.bugsnagConfiguration()
        Bugsnag.start(with: config)

        // Construct the single KMP graph. Done once; held for process lifetime.
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

// MARK: - Bugsnag configuration

private enum BugsnagConfig {
    static func bugsnagConfiguration() -> BugsnagConfiguration {
        let config = BugsnagConfiguration.loadConfig()
        // Mirror the Android / desktop setup: only forward events from production
        // builds. Development churn (simulator network hiccups, manual tests)
        // was filling the dashboard and masking real issues.
        config.enabledReleaseStages = ["production"]
        return config
    }
}
