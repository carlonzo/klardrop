import UIKit
import UniformTypeIdentifiers
import ObjectiveC

// ---------------------------------------------------------------------------
// ShareViewController (iOS) — receives files/images shared from other apps.
//
// Pure UIKit, no KMP framework: the extension only copies attachments into the
// App Group container (via ShareInbox) and hands off to the host app, which
// holds the running discovery/transfer stack. Keeping the extension lightweight
// avoids its tight memory budget and the cost of loading the KMP graph twice.
//
// Hand-off note: NSExtensionContext.open(_:) is unsupported for share
// extensions (only Today widgets may use it), so we walk the responder chain to
// reach UIApplication and ask it to open our klardrop:// URL.
// ---------------------------------------------------------------------------

class ShareViewController: UIViewController {

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        showSpinner()
        extractItems()
    }

    private func showSpinner() {
        let spinner = UIActivityIndicatorView(style: .large)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.startAnimating()
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
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
        guard let url = ShareInbox.publish(paths: paths) else {
            extensionContext?.cancelRequest(withError: NSError(
                domain: Bundle.main.bundleIdentifier ?? "KlardropShare",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No sharable content found"]
            ))
            return
        }
        openHostApp(url) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    private func openHostApp(_ url: URL, completion: @escaping () -> Void) {
        let selector = sel_registerName("openURL:")
        let openURLContextSelector = sel_registerName("openURL:completionHandler:")

        var responder: UIResponder? = self.view.window ?? self
        while let current = responder {
            if let app = current as? UIApplication {
                app.open(url, options: [:]) { _ in
                    completion()
                }
                return
            }
            if current.responds(to: selector), current != self {
                current.perform(selector, with: url)
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: completion)
                return
            }
            responder = current.next
        }

        if let context = extensionContext, context.responds(to: openURLContextSelector) {
            context.perform(openURLContextSelector, with: url, with: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: completion)
            return
        }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: completion)
    }
}
