import SwiftUI
import Bugsnag

@main
struct iOSApp: App {
    let discoveryBridge = DiscoveryBridge()

    init() {
        let config = BugsnagConfig.bugsnagConfiguration()
        Bugsnag.start(with: config)
    }

    var body: some Scene {
        WindowGroup {
            ContentView(discoveryBridge: discoveryBridge)
        }
    }
}

private enum BugsnagConfig {
    static func bugsnagConfiguration() -> BugsnagConfiguration {
        let config = BugsnagConfiguration.loadConfig()
        config.enabledReleaseStages = ["production"]
        return config
    }
}
