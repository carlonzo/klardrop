#if os(macOS)
import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// KlardropMacApp — macOS app entry point (Phase 2B).
//
// Member of the KlardropMac target ONLY. The iOSApp target has its own @main
// (iOSApp.swift). Each target compiles exactly one @main because this file
// is added only to KlardropMac and iOSApp.swift only to iosApp.
//
// No Bugsnag.start: BugsnagWrapper is a no-op on macOS and the Bugsnag pod
// is not imported in the macOS target. The Kotlin side handles any Bugsnag
// initialisation (or no-ops it) transparently.
// ---------------------------------------------------------------------------

@main
struct KlardropMacApp: App {

    private let bootstrap = KlardropBootstrap()

    var body: some Scene {
        WindowGroup {
            RootView(bootstrap: bootstrap)
                .kdColorsEnvironment()
                .preferredColorScheme(nil)
                .frame(minWidth: 720, minHeight: 480)
        }
        .defaultSize(width: 1024, height: 720)
        .commands {
            SidebarCommands()
        }
    }
}
#endif
