import UIKit
import UniformTypeIdentifiers
import ObjectiveC

class ShareViewController: UIViewController {

    private let appGroupID = "group.com.carlom.Klardrop"
    private let pendingFilesKey = "pendingFilePaths"

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        showLoadingIndicator()
        extractSharedItems()
    }

    private func showLoadingIndicator() {
        let spinner = UIActivityIndicatorView(style: .large)
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.startAnimating()
        view.addSubview(spinner)
        NSLayoutConstraint.activate([
            spinner.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            spinner.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    private func extractSharedItems() {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            finish(with: [])
            return
        }

        let group = DispatchGroup()
        var savedPaths: [String] = []
        let lock = NSLock()

        for item in extensionItems {
            for provider in (item.attachments ?? []) {
                let typeIdentifier: String
                if provider.hasItemConformingToTypeIdentifier("public.image") {
                    typeIdentifier = "public.image"
                } else if provider.hasItemConformingToTypeIdentifier("public.movie") {
                    typeIdentifier = "public.movie"
                } else if provider.hasItemConformingToTypeIdentifier("public.data") {
                    typeIdentifier = "public.data"
                } else {
                    continue
                }

                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { [weak self] (url, error) in
                    defer { group.leave() }
                    guard let self = self, let url = url, error == nil else { return }
                    if let savedPath = self.copyToSharedContainer(url: url) {
                        lock.lock()
                        savedPaths.append(savedPath)
                        lock.unlock()
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.finish(with: savedPaths)
        }
    }

    private func copyToSharedContainer(url: URL) -> String? {
        guard let containerURL = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent("shared_files", isDirectory: true) else { return nil }

        try? FileManager.default.createDirectory(at: containerURL, withIntermediateDirectories: true)

        let fileName = url.lastPathComponent.isEmpty ? "shared_file" : url.lastPathComponent
        let destURL = containerURL.appendingPathComponent(UUID().uuidString + "_" + fileName)
        do {
            try FileManager.default.copyItem(at: url, to: destURL)
            return destURL.path
        } catch {
            return nil
        }
    }

    private func finish(with paths: [String]) {
        if paths.isEmpty {
            extensionContext?.cancelRequest(withError: NSError(
                domain: Bundle.main.bundleIdentifier ?? "KlardropShare",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "No sharable content found"]
            ))
            return
        }

        let defaults = UserDefaults(suiteName: appGroupID)
        defaults?.set(paths, forKey: pendingFilesKey)
        defaults?.synchronize()

        guard let url = URL(string: "klardrop://share") else {
            extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            return
        }

        // NSExtensionContext.open(_:) is unsupported for share extensions (only Today
        // widgets may use it), so walk the responder chain to reach UIApplication and
        // ask it to open our custom scheme — the standard hand-off-to-host technique.
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
