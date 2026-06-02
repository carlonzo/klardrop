import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import presentation

// ---------------------------------------------------------------------------
// FilePicking — Native file / photo picking bridged to Filekit_corePlatformFile.
//
// Usage
// -----
// 1. Add `.fileImporterPicker(state:)` modifier to trigger document picking,
//    or `.photosImporterPicker(state:)` for the photo library.
//
// 2. After selection, each picked URL / PhotosPickerItem is:
//      a. Security-scope-accessed (fileImporter URLs).
//      b. COPIED into the app's temporary sandbox (Kotlin reads off-thread,
//         so the security-scoped bookmark won't survive the callback).
//      c. Bridged via the SKIE global free function platformFileFromPath(path:)
//         -> Filekit_corePlatformFile.
//
// 3. The resulting [Filekit_corePlatformFile] is handed to the caller's onPicked closure
//    on the main thread.
//
// IMPORTANT: Temp files are NOT deleted after handoff — the Kotlin transfer
// layer reads them asynchronously. They live in FileManager.temporaryDirectory
// and are swept by the OS on next reboot / low-storage eviction.
// ---------------------------------------------------------------------------

// MARK: - FilePickerState

/// Convenience observable that a view can hold to drive either picker.
@Observable @MainActor
final class FilePickerState {

    var showFilePicker = false
    var showPhotosPicker = false

    /// Items selected by PHPicker (bridged lazily).
    var photosPickerItems: [PhotosPickerItem] = []

    var onPicked: (([Filekit_corePlatformFile]) -> Void)?

    func presentFilePicker(onPicked: @escaping ([Filekit_corePlatformFile]) -> Void) {
        self.onPicked = onPicked
        showFilePicker = true
    }

    func presentPhotosPicker(onPicked: @escaping ([Filekit_corePlatformFile]) -> Void) {
        self.onPicked = onPicked
        showPhotosPicker = true
    }
}

// MARK: - View modifiers

extension View {

    /// Attaches the document picker sheet and handles the resolution flow.
    func fileImporterPicker(
        state: FilePickerState,
        allowedTypes: [UTType] = [.item]
    ) -> some View {
        fileImporter(
            isPresented: Binding(
                get: { state.showFilePicker },
                set: { state.showFilePicker = $0 }
            ),
            allowedContentTypes: allowedTypes,
            allowsMultipleSelection: true
        ) { result in
            Task { @MainActor in
                let files = await resolveFileImporterResult(result)
                state.onPicked?(files)
                state.showFilePicker = false
            }
        }
    }

    /// Attaches the Photos picker sheet and handles the resolution flow.
    /// Named `photosImporterPicker` to avoid collision with the system
    /// `.photosPicker` modifier introduced in PhotosUI / iOS 16.
    func photosImporterPicker(
        state: FilePickerState
    ) -> some View {
        self
            .photosPicker(
                isPresented: Binding(
                    get: { state.showPhotosPicker },
                    set: { state.showPhotosPicker = $0 }
                ),
                selection: Binding(
                    get: { state.photosPickerItems },
                    set: { state.photosPickerItems = $0 }
                ),
                maxSelectionCount: 0,
                matching: .any(of: [.images, .videos])
            )
            .onChange(of: state.photosPickerItems) { _, items in
                guard !items.isEmpty else { return }
                Task { @MainActor in
                    let files = await resolvePhotosPickerItems(items)
                    state.onPicked?(files)
                    state.photosPickerItems = []
                    state.showPhotosPicker = false
                }
            }
    }
}

// MARK: - Resolution helpers (private)

/// Resolve security-scoped fileImporter URLs into sandbox-copied Filekit_corePlatformFiles.
private func resolveFileImporterResult(_ result: Result<[URL], Error>) async -> [Filekit_corePlatformFile] {
    guard case .success(let urls) = result else { return [] }

    var platformFiles: [Filekit_corePlatformFile] = []

    for url in urls {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess { url.stopAccessingSecurityScopedResource() }
        }

        guard let dest = copyToSandbox(url: url) else { continue }
        let pf = PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
        platformFiles.append(pf)
    }

    return platformFiles
}

/// Resolve PhotosPickerItems by loading their Data and writing to a temp file.
private func resolvePhotosPickerItems(_ items: [PhotosPickerItem]) async -> [Filekit_corePlatformFile] {
    var platformFiles: [Filekit_corePlatformFile] = []

    for item in items {
        guard let data = try? await item.loadTransferable(type: Data.self) else { continue }

        // Derive a sensible extension from the item's content types.
        // supportedContentTypes is [UTType] in PhotosUI — use preferredFilenameExtension directly.
        let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "dat"
        let filename = UUID().uuidString + "." + ext

        let destDir = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
            let dest = destDir.appendingPathComponent(filename)
            try data.write(to: dest)
            let pf = PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
            platformFiles.append(pf)
        } catch {
            // Skip files that can't be written; log silently.
            continue
        }
    }

    return platformFiles
}

/// Copy a security-scoped (or plain) URL into a unique temp-sandbox directory.
/// Returns the destination URL, or nil on failure.
private func copyToSandbox(url: URL) -> URL? {
    let destDir = FileManager.default.temporaryDirectory
        .appendingPathComponent(UUID().uuidString, isDirectory: true)
    let dest = destDir.appendingPathComponent(url.lastPathComponent)
    do {
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: url, to: dest)
        return dest
    } catch {
        return nil
    }
}
