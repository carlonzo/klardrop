import SwiftUI
import presentation
#if os(iOS)
import UIKit
import QuickLook
#endif

// ---------------------------------------------------------------------------
// MessageRowView
// Dispatches a Messages row to the appropriate bubble subview.
// Mirrors: compose-ui/.../chat/DeviceChatScreen.kt  MessageRow +
//          TextMessageBubble / FileMessageBubble / UnknownMessageBubble
//
// Per-file-transfer Flow subscriptions live inside FileMessageBubble (each
// row opens its own small Task) to avoid unbounded collectors on long threads.
// ---------------------------------------------------------------------------

/// Group-gap threshold in milliseconds (mirrors Kotlin's GROUP_GAP_MILLIS = 5 min).
private let groupGapMillis: Int64 = 5 * 60 * 1000

struct MessageRowView: View {

    let message: Messages
    let model: ChatModel
    let isFirstOfGroup: Bool

    @Environment(\.kdColors) private var kd

    private var isSender: Bool { message.is_sender != 0 }
    private var direction: KdBubbleDirection { isSender ? .outgoing : .incoming }
    private var timestamp: String { ChatTimeFormat.time(message.timestamp) }
    private var topPadding: CGFloat { isFirstOfGroup ? KdSpacing.s2 : KdSpacing.s1 }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if message.message_type == "FILE", let ftId = message.file_transfer_id?.int64Value {
                FileMessageBubble(
                    message: message,
                    fileTransferId: ftId,
                    model: model,
                    direction: direction,
                    timestamp: timestamp
                )
            } else if message.message_type == "TEXT" {
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

    let message: Messages
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

    let message: Messages
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

    private var isSender: Bool { message.is_sender != 0 }

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

    private var isImage: Bool {
        let mime = fileTransfer?.mime_type ?? ""
        if !mime.isEmpty && mime != "application/octet-stream" {
            return mime.hasPrefix("image/")
        }
        let ext = (fileTransfer?.file_path ?? "")
            .split(separator: ".")
            .last
            .map(String.init) ?? ""
        return ["jpg","jpeg","png","gif","webp","heic","heif","bmp","tiff"].contains(ext.lowercased())
    }

    private var previewImageUrl: URL? {
        guard let path = fileTransfer?.file_path, !path.isEmpty else { return nil }
        let isReady = isSender ? true : fileTransfer?.status == "COMPLETED"
        guard isReady && isImage else { return nil }
        return toImageUrl(path: path)
    }

    /// Open a received file natively: QuickLook preview on iOS, open-in-default-app
    /// (NSWorkspace via the KMP layer) on macOS.
    private func open(_ path: String) {
        #if os(iOS)
        previewURL = toImageUrl(path: path)
        #else
        model.openFile(path)
        #endif
    }

    var body: some View {
        VStack(alignment: direction == .outgoing ? .trailing : .leading, spacing: 0) {
            BubbleView(direction: direction, timestamp: timestamp) {
                VStack(alignment: .leading, spacing: KdSpacing.s1) {
                    // Inline image preview
                    if let imageUrl = previewImageUrl {
                        AsyncImage(url: imageUrl) { phase in
                            if case .success(let img) = phase {
                                img
                                    .resizable()
                                    .scaledToFit()
                                    .frame(maxWidth: 320, maxHeight: KdBubbleMaxContentHeight)
                                    .clipped()
                                    .clipShape(RoundedRectangle(cornerRadius: KdRadii.sm, style: .continuous))
                                    .onTapGesture {
                                        if let p = openablePath { open(p) }
                                    }
                            }
                        }
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

    let message: Messages
    let direction: KdBubbleDirection
    let timestamp: String

    @Environment(\.kdColors) private var kd

    var body: some View {
        BubbleView(direction: direction, timestamp: timestamp) {
            Text("Unsupported message (\(message.message_type))")
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
