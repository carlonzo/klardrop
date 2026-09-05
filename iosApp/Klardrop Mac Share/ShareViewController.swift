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
                group.enter()
                extractFile(from: provider) { path in
                    defer { group.leave() }
                    if let path = path {
                        lock.lock()
                        paths.append(path)
                        lock.unlock()
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.finish(with: paths)
        }
    }

    private func extractFile(from provider: NSItemProvider, completion: @escaping (String?) -> Void) {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                if let url = item as? URL, let path = ShareInbox.ingest(url) {
                    completion(path)
                    return
                }
                // Fallback to representation loading if loadItem for fileURL did not yield a path
                self.extractViaRepresentation(from: provider, completion: completion)
            }
        } else {
            extractViaRepresentation(from: provider, completion: completion)
        }
    }

    private func extractViaRepresentation(from provider: NSItemProvider, completion: @escaping (String?) -> Void) {
        guard let typeId = bestTypeIdentifier(for: provider) else {
            completion(nil)
            return
        }

        provider.loadFileRepresentation(forTypeIdentifier: typeId) { url, _ in
            if let url = url, let path = ShareInbox.ingest(url) {
                completion(path)
                return
            }

            // Fallback: try loadItem with the specific type identifier (may return a URL or Data)
            provider.loadItem(forTypeIdentifier: typeId, options: nil) { item, _ in
                if let url = item as? URL, let path = ShareInbox.ingest(url) {
                    completion(path)
                    return
                }
                if let data = item as? Data, let path = ShareInbox.ingest(data: data, suggestedType: typeId) {
                    completion(path)
                    return
                }

                // Fallback: try loadDataRepresentation
                provider.loadDataRepresentation(forTypeIdentifier: typeId) { data, _ in
                    if let data = data, let path = ShareInbox.ingest(data: data, suggestedType: typeId) {
                        completion(path)
                        return
                    }
                    completion(nil)
                }
            }
        }
    }

    private func bestTypeIdentifier(for provider: NSItemProvider) -> String? {
        let nonFileURLTypes = provider.registeredTypeIdentifiers.filter { $0 != UTType.fileURL.identifier }
        let preferredConformances: [UTType] = [
            .image, .movie, .audio, .pdf, .text, .archive, .data, .content, .item
        ]
        for target in preferredConformances {
            if let match = nonFileURLTypes.first(where: { id in
                UTType(id)?.conforms(to: target) == true
            }) {
                return match
            }
        }
        return nonFileURLTypes.first ?? provider.registeredTypeIdentifiers.first
    }

    private func finish(with paths: [String]) {
        if let url = ShareInbox.publish(paths: paths) {
            NSWorkspace.shared.open(url)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) { [weak self] in
                self?.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            }
        } else {
            extensionContext?.cancelRequest(withError: NSError(
                domain: Bundle.main.bundleIdentifier ?? "KlardropMacShare",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No sharable content found"]
            ))
        }
    }
}
