import SwiftUI
import Bugsnag

@main
struct iOSApp: App {
    init() {
        Bugsnag.start()
    }
	var body: some Scene {
		WindowGroup {
			ContentView()
		}
	}
}
