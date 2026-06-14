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
        guard let url = ShareInbox.publish(paths: paths) else {
            extensionContext?.cancelRequest(withError: NSError(
                domain: Bundle.main.bundleIdentifier ?? "KlardropShare",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No sharable content found"]
            ))
            return
        }
        openHostApp(url)
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }

    @discardableResult
    private func openHostApp(_ url: URL) -> Bool {
        let selector = sel_registerName("openURL:")
        var responder: UIResponder? = self
        while let current = responder {
            if current.responds(to: selector), current != self {
                current.perform(selector, with: url)
                return true
            }
            responder = current.next
        }
        return false
    }
}
