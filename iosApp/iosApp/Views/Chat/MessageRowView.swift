import SwiftUI
import presentation
import AVFoundation
import ImageIO
import Photos
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
import QuickLook
#elseif os(macOS)
import AppKit
#endif

#if os(iOS)
private typealias KdPlatformImage = UIImage
#elseif os(macOS)
private typealias KdPlatformImage = NSImage
#endif

// ---------------------------------------------------------------------------
// MessageRowView
// Dispatches a ChatMessage to the appropriate bubble subview.
// Mirrors: compose-ui/.../chat/DeviceChatScreen.kt  MessageRow +
//          TextMessageBubble / FileMessageBubble / UnknownMessageBubble
//
// Per-file-transfer Flow subscriptions live inside FileMessageBubble (each
// row opens its own small Task) to avoid unbounded collectors on long threads.
//
// Consumes ChatMessage (the merged UI-facing type returned by
// MessageRepository.getMessagesForDevice), not the raw SQLDelight Messages row.
// ---------------------------------------------------------------------------

/// Group-gap threshold in milliseconds (mirrors Kotlin's GROUP_GAP_MILLIS = 5 min).
private let groupGapMillis: Int64 = 5 * 60 * 1000

struct MessageRowView: View {

    let message: ChatMessage
    let model: ChatModel
    let isFirstOfGroup: Bool

    @Environment(\.kdColors) private var kd

    private var isSender: Bool { message.isSender }
    private var direction: KdBubbleDirection { isSender ? .outgoing : .incoming }
    private var timestamp: String { ChatTimeFormat.time(message.timestamp) }
    private var topPadding: CGFloat { isFirstOfGroup ? KdSpacing.s2 : KdSpacing.s1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if message.messageType == "FILE", let ftId = message.fileTransferId?.int64Value {
                FileMessageBubble(
                    message: message,
                    fileTransferId: ftId,
                    model: model,
                    direction: direction,
                    timestamp: timestamp
                )
            } else if message.messageType == "TEXT" {
                TextMessageBubble(
                    message: message,
                    direction: direction,
                    timestamp: timestamp,
                    model: model
                )
            } else {
                UnknownMessageBubble(
                    message: message,
                    direction: direction,
                    timestamp: timestamp
                )
            }
        }
        .padding(.top, topPadding)
    }
}

// MARK: - TextMessageBubble

private struct TextMessageBubble: View {

    let message: ChatMessage
    let direction: KdBubbleDirection
    let timestamp: String
    let model: ChatModel

    @Environment(\.kdColors) private var kd
    @State private var overflowing = false
    @State private var showViewer = false

    /// Detects if the message content is a tappable URL (via Kotlin free fn in :presentation).
    private var openableUrl: String? {
        UrlDetectionKt.openableUrlOrNull(text: message.content)
    }

    var body: some View {
        VStack(alignment: direction == .outgoing ? .trailing : .leading, spacing: 0) {
            BubbleView(direction: direction, timestamp: timestamp) {
                if let url = openableUrl {
                    // URL variant: styled as accent underlined link
                    Text(message.content)
                        .kdStyle(.body, color: kd.accent)
                        .underline(true, color: kd.accent)
                        .onTapGesture { model.openUrl(url) }
                        .frame(maxWidth: .infinity, alignment: .leading)
                } else {
                    // Regular text: clipped at KdBubbleMaxContentHeight
                    Text(message.content)
                        .kdStyle(.body, color: kd.text, multiline: true)
                        .textSelection(.enabled)
                        .lineLimit(nil)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(maxHeight: KdBubbleMaxContentHeight, alignment: .topLeading)
                        .clipped()
                        .background(
                            GeometryReader { geo in
                                Color.clear.preference(
                                    key: TextHeightKey.self,
                                    value: geo.size.height
                                )
                            }
                        )
                        .onPreferenceChange(TextHeightKey.self) { height in
                            overflowing = height >= KdBubbleMaxContentHeight - 1
                        }
                }
            }

            // Quick actions row
            BubbleQuickActionsView(direction: direction) {
                QuickActionButton(
                    systemImage: "doc.on.doc",
                    accessibility: "Copy text"
                ) {
                    model.copyText(message.content)
                }
                if overflowing {
                    QuickActionButton(
                        systemImage: "arrow.up.left.and.arrow.down.right",
                        accessibility: "Show full message"
                    ) {
                        showViewer = true
                    }
                }
            }
        }
        .sheet(isPresented: $showViewer) {
            TextMessageViewerView(
                text: message.content,
                onCopy: { model.copyText(message.content) },
                onDismiss: { showViewer = false }
            )
            #if os(iOS)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .presentationCornerRadius(KdRadii.sheet)
            #endif
        }
    }
}

// MARK: - TextHeightKey (preference to detect overflow)

private struct TextHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

// MARK: - FileMessageBubble

private struct FileMessageBubble: View {

    let message: ChatMessage
    let fileTransferId: Int64
    let model: ChatModel
    let direction: KdBubbleDirection
    let timestamp: String

    @Environment(\.kdColors) private var kd

    /// Per-row file-transfer state, bridged from the Kotlin Flow via .task.
    /// SKIE wraps Flow<File_transfers?> as SkieSwiftOptionalFlow which is AsyncSequence.
    @State private var fileTransfer: File_transfers? = nil

    #if os(iOS)
    /// Drives the native QuickLook preview for a received file (iOS can't open
    /// arbitrary files from the KMP layer; QuickLook is the native presenter).
    @State private var previewURL: URL? = nil
    #endif

    private var isSender: Bool { message.isSender }

    private var fileState: KdFileState {
        guard let ft = fileTransfer else {
            return isSender ? .sending(0) : .receiving(0)
        }
        switch ft.status {
        case "IN_PROGRESS":
            let progress: Double = ft.total_size > 0
                ? Double(ft.transferred_size) / Double(ft.total_size)
                : 0
            return isSender ? .sending(progress) : .receiving(progress)
        case "COMPLETED":
            return .done
        case "FAILED", "REJECTED":
            return .failed
        default:
            return isSender ? .sending(0) : .receiving(0)
        }
    }

    private var openablePath: String? {
        guard !isSender,
              fileTransfer?.status == "COMPLETED",
              let path = fileTransfer?.file_path,
              !path.isEmpty
        else { return nil }
        return path
    }

    private var displayName: String {
        fileTransfer?.file_name ?? message.content
    }

    private var displaySize: String? {
        guard let total = fileTransfer?.total_size, total > 0 else { return nil }
        return ChatTimeFormat.bytesFormatted(total)
    }

    private var fileExtension: String {
        (fileTransfer?.file_path ?? "")
            .split(separator: ".")
            .last
            .map(String.init)?.lowercased() ?? ""
    }

    private var isImage: Bool {
        let mime = fileTransfer?.mime_type ?? ""
        if !mime.isEmpty && mime != "application/octet-stream" {
            return mime.hasPrefix("image/")
        }
        return ["jpg","jpeg","png","gif","webp","heic","heif","bmp","tiff"].contains(fileExtension)
    }

    private var isVideo: Bool {
        let mime = fileTransfer?.mime_type ?? ""
        if !mime.isEmpty && mime != "application/octet-stream" {
            return mime.hasPrefix("video/")
        }
        return ["mp4","mov","m4v","webm","mkv","avi","3gp"].contains(fileExtension)
    }

    /// Local path to render a thumbnail for — only when the file is a ready image/video.
    private var thumbnailPath: String? {
        guard let path = fileTransfer?.file_path, !path.isEmpty else { return nil }
        let isReady = isSender ? true : fileTransfer?.status == "COMPLETED"
        guard isReady && (isImage || isVideo) else { return nil }
        return path
    }

    /// Open a received file natively: QuickLook preview on iOS, open-in-default-app
    /// (NSWorkspace via the KMP layer) on macOS.
    private func open(_ path: String) {
        #if os(iOS)
        if let assetId = photosLocalIdentifier(from: path) {
            // Photos-backed media (received on iOS): export the asset to a temp file QuickLook can open.
            Task { previewURL = await exportPhotosAssetToTempURL(localIdentifier: assetId) }
        } else {
            previewURL = toImageUrl(path: path)
        }
        #else
        model.openFile(path)
        #endif
    }

    var body: some View {
        VStack(alignment: direction == .outgoing ? .trailing : .leading, spacing: 0) {
            BubbleView(direction: direction, timestamp: timestamp) {
                VStack(alignment: .leading, spacing: KdSpacing.s1) {
                    // Inline image / video thumbnail
                    if let tpath = thumbnailPath {
                        FileThumbnailView(path: tpath, isVideo: isVideo, maxHeight: KdBubbleMaxContentHeight)
                            .onTapGesture { open(openablePath ?? tpath) }
                    }

                    // File card
                    FileCardView(
                        fileName: displayName,
                        fileSize: displaySize,
                        state: fileState,
                        onRetry: {
                            if isSender { model.retryFile(fileTransferId) }
                        }
                    )
                    .onTapGesture {
                        if let p = openablePath { open(p) }
                    }
                }
            }

            // Quick action: open file
            if let path = openablePath {
                BubbleQuickActionsView(direction: direction) {
                    QuickActionButton(
                        systemImage: "arrow.up.forward.square",
                        accessibility: "Open file"
                    ) {
                        open(path)
                    }
                }
            }
        }
        // One Task per row — .task auto-cancels when the row disappears,
        // tearing down the Kotlin flow collector so no leaks accumulate.
        .task(id: fileTransferId) {
            let rawFlow = model.viewModel.messageRepository.getFileTransferById(id: fileTransferId)
            // SKIE bridges Flow<File_transfers?> -> SkieSwiftOptionalFlow<File_transfers>
            // which is AsyncSequence. Cast is safe; SKIE guarantees this bridge.
            if let asyncFlow = rawFlow as? SkieSwiftOptionalFlow<File_transfers> {
                do {
                    for try await ft in asyncFlow {
                        self.fileTransfer = ft
                    }
                } catch {}
            }
        }
        #if os(iOS)
        .sheet(isPresented: Binding(
            get: { previewURL != nil },
            set: { if !$0 { previewURL = nil } }
        )) {
            if let url = previewURL {
                QuickLookPreview(url: url).ignoresSafeArea()
            }
        }
        #endif
    }
}

#if os(iOS)
// MARK: - QuickLookPreview (native file preview)

/// Wraps QLPreviewController so received files open in the system previewer.
private struct QuickLookPreview: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UINavigationController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return UINavigationController(rootViewController: controller)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(url: url) }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        let url: URL
        init(url: URL) { self.url = url }
        func numberOfPreviewItems(in controller: QLPreviewController) -> Int { 1 }
        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}
#endif

// MARK: - UnknownMessageBubble

private struct UnknownMessageBubble: View {

    let message: ChatMessage
    let direction: KdBubbleDirection
    let timestamp: String

    @Environment(\.kdColors) private var kd

    var body: some View {
        BubbleView(direction: direction, timestamp: timestamp) {
            Text("Unsupported message (\(message.messageType))")
                .kdStyle(.caption, color: kd.err)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: - Image URL helper (mirrors Kotlin toImageModel)

/// Converts a persisted file path to a URL that AsyncImage can load.
/// content:// -> passed through; content:/ (MediaStore collapsed) -> restored;
/// plain path -> file:// URL.
private func toImageUrl(path: String) -> URL? {
    guard !path.isEmpty else { return nil }
    if path.hasPrefix("content://") {
        return URL(string: path)
    } else if path.hasPrefix("content:/") {
        let restored = "content://" + path.dropFirst("content:/".count)
        return URL(string: restored)
    } else {
        return URL(fileURLWithPath: path)
    }
}

// MARK: - FileThumbnailView (inline image + video previews)

/// Renders a downsized thumbnail for a local image or video file. Loads off the main
/// thread; shows nothing until ready (the FileCardView below still carries name/size).
private struct FileThumbnailView: View {
    let path: String
    let isVideo: Bool
    var maxHeight: CGFloat

    @State private var image: KdPlatformImage?

    var body: some View {
        Group {
            if let image {
                ZStack {
                    #if os(iOS)
                    Image(uiImage: image).resizable().scaledToFit()
                    #elseif os(macOS)
                    Image(nsImage: image).resizable().scaledToFit()
                    #endif

                    if isVideo {
                        Image(systemName: "play.circle.fill")
                            .font(.system(size: 40))
                            .symbolRenderingMode(.palette)
                            .foregroundStyle(.white, .black.opacity(0.45))
                    }
                }
                .frame(maxWidth: 320, maxHeight: maxHeight)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: KdRadii.sm, style: .continuous))
            }
        }
        .task(id: path) {
            if let assetId = photosLocalIdentifier(from: path) {
                image = await loadPhotosThumbnail(localIdentifier: assetId, maxPixel: 640)
            } else {
                image = await loadFileThumbnail(path: path, isVideo: isVideo)
            }
        }
    }
}

/// Loads a downsized thumbnail for a local file path — image via ImageIO, video via
/// AVAssetImageGenerator. Returns nil on failure (missing/unsupported file).
private func loadFileThumbnail(path: String, isVideo: Bool) async -> KdPlatformImage? {
    let url: URL = path.hasPrefix("file://")
        ? (URL(string: path) ?? URL(fileURLWithPath: path))
        : URL(fileURLWithPath: path)

    if isVideo {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 640, height: 640)
        let time = CMTime(seconds: 0.1, preferredTimescale: 600)
        guard let result = try? await generator.image(at: time) else { return nil }
        return platformImage(from: result.image)
    } else {
        return await Task.detached(priority: .userInitiated) {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 640,
            ]
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
            else { return nil }
            return platformImage(from: cg)
        }.value
    }
}

private func platformImage(from cg: CGImage) -> KdPlatformImage {
    #if os(iOS)
    return UIImage(cgImage: cg)
    #elseif os(macOS)
    return NSImage(cgImage: cg, size: NSSize(width: cg.width, height: cg.height))
    #endif
}

// MARK: - Photos-backed media (iOS received images/videos)

/// Received media on iOS is saved to the Photos library; the Kotlin layer records a
/// "ph://<localIdentifier>" reference in file_path. kotlinx.io's Path collapses the "//" to
/// "/", so the persisted value can arrive as either "ph://…" or "ph:/…" — handle both
/// (mirrors the existing "content://" / "content:/" handling).
private func photosLocalIdentifier(from path: String) -> String? {
    if path.hasPrefix("ph://") { return String(path.dropFirst("ph://".count)) }
    if path.hasPrefix("ph:/") { return String(path.dropFirst("ph:/".count)) }
    return nil
}

/// Loads a downsized thumbnail for a Photos asset (works for both image and video assets —
/// videos return their poster frame). Returns nil if the asset can't be fetched (e.g. the
/// user denied full Photos access).
private func loadPhotosThumbnail(localIdentifier: String, maxPixel: CGFloat) async -> KdPlatformImage? {
    guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject
    else { return nil }

    let options = PHImageRequestOptions()
    options.isNetworkAccessAllowed = true   // allow fetching from iCloud Photos if needed
    options.deliveryMode = .highQualityFormat // single callback (no progressive degraded results)
    options.resizeMode = .fast
    let target = CGSize(width: maxPixel, height: maxPixel)

    return await withCheckedContinuation { (cont: CheckedContinuation<KdPlatformImage?, Never>) in
        PHImageManager.default().requestImage(
            for: asset,
            targetSize: target,
            contentMode: .aspectFit,
            options: options
        ) { image, _ in
            cont.resume(returning: image)
        }
    }
}

#if os(iOS)
/// Exports a Photos asset to a temp file so QuickLook can present it (QuickLook needs a file URL;
/// a PHAsset is not directly openable). Images are written from their data; videos are passthrough-
/// exported. Returns nil on failure.
private func exportPhotosAssetToTempURL(localIdentifier: String) async -> URL? {
    guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [localIdentifier], options: nil).firstObject
    else { return nil }

    if asset.mediaType == .video {
        return await withCheckedContinuation { (cont: CheckedContinuation<URL?, Never>) in
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            PHImageManager.default().requestExportSession(
                forVideo: asset,
                options: options,
                exportPreset: AVAssetExportPresetPassthrough
            ) { session, _ in
                guard let session else { cont.resume(returning: nil); return }
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension("mov")
                session.outputURL = temp
                session.outputFileType = .mov
                session.exportAsynchronously {
                    cont.resume(returning: session.status == .completed ? temp : nil)
                }
            }
        }
    } else {
        return await withCheckedContinuation { (cont: CheckedContinuation<URL?, Never>) in
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            PHImageManager.default().requestImageDataAndOrientation(for: asset, options: options) { data, dataUTI, _, _ in
                guard let data else { cont.resume(returning: nil); return }
                let ext = dataUTI.flatMap { UTType($0)?.preferredFilenameExtension } ?? "jpg"
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension(ext)
                do {
                    try data.write(to: temp)
                    cont.resume(returning: temp)
                } catch {
                    cont.resume(returning: nil)
                }
            }
        }
    }
}
#endif
