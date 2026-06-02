import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// RootView — SwiftUI root (Phase 1B-1; replaces Compose ContentView bridge).
//
// For 1B-1 this is a thin wrapper that hosts DiscoveryView inside a
// KdColors.bg0 full-bleed background. In 1B-2 it grows to be size-class
// aware: compact (iPhone) uses NavigationStack, regular (iPad) uses
// NavigationSplitView with Sidebar.
//
// The bootstrap is passed by reference so all descendant screens share the
// same Klardrop graph and DiscoveryController instance.
// ---------------------------------------------------------------------------

struct RootView: View {

    let bootstrap: KlardropBootstrap
    @Environment(\.kdColors) private var kd

    var body: some View {
        DiscoveryView(bootstrap: bootstrap)
            .background(kd.bg0.ignoresSafeArea())
    }
}
