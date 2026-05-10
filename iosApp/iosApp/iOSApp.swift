import SwiftUI
import Bugsnag

@main
struct iOSApp: App {
    init() {
        // Mirror the Android/desktop setup: only forward events from production
        // builds. Development churn (manual disconnect tests, simulator network
        // hiccups) was filling the dashboard and masking real issues.
        let config = BugsnagConfig.bugsnagConfiguration()
        Bugsnag.start(with: config)
    }
	var body: some Scene {
		WindowGroup {
			ContentView()
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
