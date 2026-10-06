#if os(macOS)
import SwiftUI
import AppKit
import presentation
import control_plane

// ---------------------------------------------------------------------------
// KlardropMacApp — macOS app entry point (Phase 2B).
//
// Member of the KlardropMac target ONLY. The iOSApp target has its own @main
// (iOSApp.swift). Each target compiles exactly one @main because this file
// is added only to KlardropMac and iOSApp.swift only to iosApp.
//
// Crash reporting is started by KlardropBootstrap on the Kotlin side (Sentry KMP
// has a real macOS artifact), so there is no SDK import here any more.
//
// Menu bar: a MenuBarExtra mirrors the JVM desktop tray (Main.kt) — a white
// template drop glyph that opens the app, opens a specific device, or quits.
// The single DiscoveryAppModel is owned here (not in RootView) so the menu and
// the window share the one DiscoveryController, and discovery keeps running
// while the window is closed.
// ---------------------------------------------------------------------------

@main
struct KlardropMacApp: App {

    private let bootstrap: presentation.KlardropBootstrap

    // App-owned so the MenuBarExtra and the window share a single
    // DiscoveryController (never construct a second one — see DiscoveryAppModel).
    // Held for the process lifetime: the menu bar must keep showing devices even
    // after the main window is closed.
    @State private var model: DiscoveryAppModel

    init() {
        // Constructing the bootstrap also starts Sentry (see KlardropBootstrap).
        // The default controlPort is an ephemeral loopback port; KLARDROP_CONTROL_PORT
        // can pin it, and -1 disables local IPC entirely.
        let bootstrap = presentation.KlardropBootstrap()
        self.bootstrap = bootstrap
        let model = DiscoveryAppModel(bootstrap: bootstrap)
        _model = State(initialValue: model)

        // Publish and serve the local control plane (see startLocalControlPlane).
        // model.controller is the SAME DiscoveryController the menu bar and the window
        // render from, so the control plane observes one shared engine.
        KlardropMacApp.startLocalControlPlane(app: bootstrap.klardrop,
                                             controller: model.controller)
    }

    // -----------------------------------------------------------------------
    // Local control plane — what makes `klardrop devices/status/share` work
    // against the running macOS app.
    //
    // :control-plane depends on :presentation, so KlardropBootstrap cannot
    // reference ControlPlane without a module cycle. The cycle is broken the
    // other way round: :control-plane ships its own macOS framework
    // (control_plane.framework, built by `:control-plane:linkReleaseFrameworkMacosArm64`
    // and found through the KlardropMac target's FRAMEWORK_SEARCH_PATHS), and this
    // file imports it. One implementation, no per-platform copy of the server.
    //
    // start() binds 127.0.0.1 and publishes control.json — port, token and the
    // capability list — into the app's App Group container, the only location a
    // sandboxed app and the unsandboxed `klardrop` CLI can both reach. bind()
    // then wires the DiscoveryController in; until it runs, the submission routes
    // answer 503 rather than pretending an engine is attached.
    //
    // Runs off the main actor: start() binds a socket and publishes a file, and
    // bind() launches the flow collectors on the engine's app scope.
    // -----------------------------------------------------------------------

    private static func startLocalControlPlane(app: presentation.Klardrop, controller: presentation.DiscoveryController) {
        Task.detached(priority: .background) {
            do {
                try await ControlPlane.shared.start(app: app)
                try await ControlPlane.shared.bind(discoveryController: controller, app: app)
            } catch {
                // A control plane that cannot start must not take the app down with it: the
                // GUI is fully usable without local IPC, so this is a diagnostic, not a crash.
                NSLog("Klardrop: control plane unavailable: \(error.localizedDescription)")
            }
        }
    }

    var body: some Scene {
        WindowGroup(id: KlardropMacApp.mainWindowID) {
            RootView(bootstrap: bootstrap, model: model)
                .kdColorsEnvironment()
                .preferredColorScheme(nil)
                .frame(minWidth: 720, minHeight: 480)
        }
        .defaultSize(width: 1024, height: 720)
        .commands {
            SidebarCommands()
        }

        MenuBarExtra {
            MenuBarContent(model: model)
        } label: {
            Image(nsImage: MenuBarIconFactory.dropTemplateImage())
        }
    }

    static let mainWindowID = "main"
}

// ---------------------------------------------------------------------------
// MenuBarContent — the pull-down menu shown from the status bar glyph.
//
// Mirrors the JVM tray (desktop/Main.kt): "Open Klardrop", the discovered
// device list (sorted by name, click opens that device), then "Quit Klardrop".
// The menu is built lazily each time it opens, so it always reflects the live
// device list from the shared DiscoveryController.
// ---------------------------------------------------------------------------

private struct MenuBarContent: View {

    let model: DiscoveryAppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Open Klardrop") {
            activateApp()
        }

        Divider()

        let devices = model.state.devices
            .sorted { $0.deviceName.lowercased() < $1.deviceName.lowercased() }

        if devices.isEmpty {
            Button("No devices found") {}
                .disabled(true)
        } else {
            ForEach(devices, id: \.deviceId) { device in
                Button(device.deviceName) {
                    open(device)
                }
            }
        }

        Divider()

        Button("Quit Klardrop") {
            NSApp.terminate(nil)
        }
    }

    // MARK: - Actions

    /// Bring the app forward and show the main window (reusing it if already
    /// open, mirroring the tray's onAction = show-window behaviour).
    private func activateApp() {
        NSApp.activate(ignoringOtherApps: true)
        openWindow(id: KlardropMacApp.mainWindowID)
    }

    /// Open the app and select the tapped device — same flow as a sidebar tap
    /// in KlardropNav (select chat, notify controller, mark active chat).
    private func open(_ device: presentation.DeviceUi) {
        activateApp()
        let route = ChatRoute(deviceId: device.deviceId, deviceName: device.deviceName)
        model.selectedChat = route
        model.onDeviceTap(device)
        model.setActiveChatDeviceId(device.deviceId)
    }
}

// ---------------------------------------------------------------------------
// MenuBarIconFactory — draws the Klardrop drop as a vector template NSImage.
//
// Reuses the same drop bezier as the JVM menu bar glyph (desktop resources/
// icons/menubar.svg). Marked isTemplate so macOS tints it for the current menu
// bar appearance (white on a dark menu bar, black on a light one). Drawn from a
// path rather than a rasterised asset, so it stays crisp at any size and needs
// no asset-catalog entry (the KlardropMac target doesn't bundle Assets.xcassets).
// ---------------------------------------------------------------------------

private enum MenuBarIconFactory {

    static func dropTemplateImage() -> NSImage {
        let side: CGFloat = 18
        let image = NSImage(size: NSSize(width: side, height: side), flipped: true) { _ in
            // Centre + scale the SVG's centred path (44x44 viewBox) into the box.
            let center: CGFloat = side / 2
            let scale: CGFloat = 0.45
            func point(_ x: CGFloat, _ y: CGFloat) -> NSPoint {
                NSPoint(x: center + x * scale, y: center + y * scale)
            }

            let path = NSBezierPath()
            path.move(to: point(0, -13))
            path.curve(to: point(-8.4, 11.6),
                       controlPoint1: point(-8.4, -2.6),
                       controlPoint2: point(-11.4, 5.0))
            path.curve(to: point(8.4, 11.6),
                       controlPoint1: point(-5.4, 18.0),
                       controlPoint2: point(5.4, 18.0))
            path.curve(to: point(0, -13),
                       controlPoint1: point(11.4, 5.0),
                       controlPoint2: point(8.4, -2.6))
            path.close()

            NSColor.black.setFill()
            path.fill()
            return true
        }
        image.isTemplate = true
        return image
    }
}
#endif
