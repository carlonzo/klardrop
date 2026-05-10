import UIKit
import SwiftUI
import common_ui


struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        
        
        DiscoveryBridge().RootKlardropApp()
        
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
                .ignoresSafeArea(.keyboard) // Compose has own keyboard handler
                .background(
                    // Paint the safe-area edges (status bar / home indicator)
                    // with the Compose Surface tone (KdColors bg0 = #181B20).
                    // Without this, iPadOS renders system black behind the
                    // status bar and it looks disconnected from the app.
                    Color(red: 0x18/255.0, green: 0x1B/255.0, blue: 0x20/255.0)
                        .ignoresSafeArea()
                )
    }
}



