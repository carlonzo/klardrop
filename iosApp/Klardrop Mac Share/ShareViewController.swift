import Cocoa
import UniformTypeIdentifiers

// ---------------------------------------------------------------------------
// ShareViewController (macOS) — receives files/images shared from other apps
// via the system Share menu (NSSharingService).
//
// Mirrors the iOS extension: copy attachments into the App Group container,
// then hand off to the host app, which holds the running discovery/transfer
// stack. On macOS the host is opened through NSWorkspace (the iOS
// responder-chain trick does not apply).
//
// No visible UI: the extension processes its inputs and completes immediately.
// ---------------------------------------------------------------------------

class ShareViewController: NSViewController {

    override func loadView() {
        // A zero-size view: we present no UI and finish as soon as the
        // attachments are copied.
        view = NSView(frame: NSRect(x: 0, y: 0, width: 1, height: 1))
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        extractItems()
    }

    private func extractItems() {
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        let group = DispatchGroup()
        let lock = NSLock()
        var paths: [String] = []

        for item in items {
            for provider in (item.attachments ?? []) {
                guard let type = preferredType(for: provider) else { continue }
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                    defer { group.leave() }
                    guard let url else { return }
                    if let path = ShareInbox.ingest(url) {
                        lock.lock(); paths.append(path); lock.unlock()
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.finish(with: paths)
        }
    }

    private func preferredType(for provider: NSItemProvider) -> String? {
        for type in [UTType.image, UTType.movie, UTType.fileURL, UTType.data] {
            if provider.hasItemConformingToTypeIdentifier(type.identifier) {
                return type.identifier
            }
        }
        return nil
    }

    private func finish(with paths: [String]) {
        if let url = ShareInbox.publish(paths: paths) {
            NSWorkspace.shared.open(url)
            extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        } else {
            extensionContext?.cancelRequest(withError: NSError(
                domain: Bundle.main.bundleIdentifier ?? "KlardropMacShare",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No sharable content found"]
            ))
        }
    }
}
