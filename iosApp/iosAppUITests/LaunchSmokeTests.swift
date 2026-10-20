import XCTest

// Launches the real iOS app. PermissionsPanelView reads PermissionsState.capabilities
// on the first SwiftUI frame, so a bridge abort (KLARDROP-KJ) fails this test.
final class LaunchSmokeTests: XCTestCase {
    func testAppDoesNotCrashOnStartup() {
        let app = XCUIApplication()
        app.launch()

        // The local-network prompt can appear once discovery starts. Dismiss it
        // if it shows up; the startup crash happens whether or not it does.
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) {
            allow.tap()
        }

        XCTAssertEqual(app.state, .runningForeground)
    }
}
