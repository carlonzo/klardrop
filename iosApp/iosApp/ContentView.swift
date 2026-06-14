import UIKit
import SwiftUI
import common_ui

struct ComposeView: UIViewControllerRepresentable {
    let discoveryBridge: DiscoveryBridge

    func makeUIViewController(context: Context) -> UIViewController {
        discoveryBridge.RootKlardropApp()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    let discoveryBridge: DiscoveryBridge
    @State private var showShareSheet = false
    @State private var pendingFilePaths: [String] = []

    var body: some View {
        ComposeView(discoveryBridge: discoveryBridge)
            .ignoresSafeArea(.keyboard)
            .background(
                Color(red: 0x18/255.0, green: 0x1B/255.0, blue: 0x20/255.0)
                    .ignoresSafeArea()
            )
            .onOpenURL { url in
                handleIncomingURL(url)
            }
            .sheet(isPresented: $showShareSheet) {
                ShareSheetView(
                    bridge: discoveryBridge,
                    filePaths: pendingFilePaths,
                    onDismiss: {
                        showShareSheet = false
                        for path in pendingFilePaths {
                            try? FileManager.default.removeItem(atPath: path)
                        }
                        pendingFilePaths = []
                    }
                )
            }
    }

    private func handleIncomingURL(_ url: URL) {
        guard url.scheme == "klardrop", url.host == "share" else { return }
        let defaults = UserDefaults(suiteName: "group.com.carlom.Klardrop")
        guard let paths = defaults?.stringArray(forKey: "pendingFilePaths"), !paths.isEmpty else { return }
        defaults?.removeObject(forKey: "pendingFilePaths")
        defaults?.synchronize()
        pendingFilePaths = paths
        showShareSheet = true
    }
}

struct ShareSheetView: UIViewControllerRepresentable {
    let bridge: DiscoveryBridge
    let filePaths: [String]
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UIViewController {
        bridge.makeShareViewController(filePaths: filePaths, onDismiss: onDismiss)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
