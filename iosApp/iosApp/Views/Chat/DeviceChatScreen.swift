import SwiftUI
#if os(iOS)
import UIKit
import PhotosUI
import Photos
#endif
import presentation

// ---------------------------------------------------------------------------
// DeviceChatScreen
// Full chat screen. Matches the structure of compose-ui/.../chat/DeviceChatScreen.kt.
//
// Layout:
//   - pendingAuth IncomingAuthBanner (if present)
//   - Offline BannerView (if !isOwned && unreachable)
//   - Empty state centered (DeviceAvatar + prompts + Files/Photos chips) OR
//     reversed message list (newest at bottom, day DateChips, grouped rows)
//   - MessageInputView pinned to bottom safe area
//
// File picking: native SwiftUI .fileImporter + PhotosPicker.
// Bridge to Kotlin: platformFileFromPath() free function from :presentation.
//
// On iPhone: pushed in NavigationStack with the toolbar header (.principal).
// On iPad: rendered in split detail, same .principal toolbar header.
// ---------------------------------------------------------------------------

struct DeviceChatScreen: View {

    @State var model: ChatModel
    let deviceName: String
    let isOwned: Bool
    var deviceKind: KdDeviceKind = .unknown

    @Environment(\.kdColors) private var kd
    @Environment(\.dismiss) private var dismiss

    // File picking state
    @State private var showFilePicker = false
    #if os(iOS)
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showChooser = false
    #endif
    @State private var showToast: String? = nil

    private func onAttachTapped() {
        // Mobile/tablet (iOS/iPadOS) open the Gallery/Files/Paste chooser; macOS
        // keeps the plain file picker.
        #if os(iOS)
        showChooser = true
        #else
        showFilePicker = true
        #endif
    }

    // Reachability-derived flags
    private var isOffline: Bool {
        if isOwned { return false }
        switch onEnum(of: model.reachability) {
        case .unreachable: return true
        default: return false
        }
    }

    private var headerStatus: KdStatus? {
        if isOwned { return nil }
        switch onEnum(of: model.reachability) {
        case .reachable:   return .ok
        case .unreachable: return .err
        default:           return .warn
        }
    }

    private var headerSubText: String {
        if isOwned { return "" }
        switch onEnum(of: model.reachability) {
        case .unreachable: return "Offline"
        case .probing:     return "Connecting\u{2026}"
        default:           return ""
        }
    }

    private var headerAvatarStyle: KdAvatarStyle { isOwned ? .tinted : .neutral }

    // Messages oldest-first — natural chat order (newest at the bottom).
    // Type is [ChatMessage] (was [Messages]).
    private var sortedMessages: [ChatMessage] {
        model.messages.sorted { $0.timestamp < $1.timestamp }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            kd.bg0.ignoresSafeArea()

            VStack(spacing: 0) {
                #if os(macOS)
                // On macOS, render the chat header inline since we don't use .toolbar
                ChatHeaderView(
                    deviceName: deviceName,
                    subText: headerSubText,
                    kind: deviceKind,
                    avatarStyle: headerAvatarStyle,
                    status: headerStatus,
                    isReachable: !isOffline,
                    avatarSize: 28
                )
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s2)
                Divider()
                #endif
                // Pending auth banner
                if let auth = model.pendingAuth {
                    IncomingAuthBannerView(update: auth)
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.vertical, KdSpacing.s2)
                }

                // Offline banner
                if isOffline {
                    BannerView(
                        tone: .err,
                        title: "Device is offline",
                        body: "You\u{2019}ll be reconnected automatically when the device is reachable."
                    )
                    .padding(.horizontal, KdSpacing.s3)
                    .padding(.vertical, KdSpacing.s2)
                }

                // Transfer phases that carry no percentage and, early on, no bubble either:
                // the file's chat row is only inserted once the connection is up, so between
                // "user hit send" and "bytes flowing" there would otherwise be no feedback.
                if let statusText = model.uiState.fileTransferStatusText {
                    TransferStatusStripView(text: statusText)
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.vertical, KdSpacing.s2)
                }

                // Message list or empty state
                if sortedMessages.isEmpty {
                    ChatEmptyStateView(
                        deviceName: deviceName,
                        isOwned: isOwned,
                        onPickFiles: { showFilePicker = true },
                        onPickPhotos: { /* handled by PhotosPicker onChange */ }
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    // Photo chip wraps its own PhotosPicker via overlay
                    .overlay(alignment: .center) {
                        // The Photos chip in ChatEmptyStateView uses a PhotosPicker
                        // so we don't need an additional sheet here.
                        EmptyView()
                    }
                } else {
                    MessageListView(
                        messages: sortedMessages,
                        model: model,
                        isOffline: isOffline
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                // Message input (outside safe area bottom)
                MessageInputView(
                    text: Bindable(model).draft,
                    onSend: { model.send() },
                    onAttach: { onAttachTapped() },
                    enabled: !isOffline
                )
                .background(kd.bg0)
                .padding(.bottom, KdSpacing.s1)
                // Attachment chooser: popover anchored to the input on iPad,
                // auto-adapting to a bottom sheet on iPhone (compact width).
                #if os(iOS)
                .popover(
                    isPresented: $showChooser,
                    attachmentAnchor: .point(.bottomLeading),
                    arrowEdge: .bottom
                ) {
                    AttachmentChooserView(model: model, isPresented: $showChooser)
                }
                #endif
            }
        }
        // Navigation bar (iOS/iPadOS): compact device header in the .principal slot.
        // It must NOT go in .topBarLeading — there UIKit sizes it against the space
        // left over by the back button / sidebar toggle and collapses the name to
        // nothing. navigationTitle stays set so the bar has a stable title (and the
        // back button gets a label) even while the principal view is laid out.
        // The subline is always present, only faded out, so the item's view identity
        // never changes as reachability flips — that churn made the bar redraw empty.
        #if os(iOS)
        .navigationTitle(deviceName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: KdSpacing.s2) {
                    DeviceAvatarView(
                        kind: deviceKind,
                        style: headerAvatarStyle,
                        status: headerStatus,
                        size: 30
                    )
                    VStack(alignment: .leading, spacing: 1) {
                        Text(deviceName)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(kd.text)
                            .lineLimit(1)
                        Text(headerSubText.isEmpty ? " " : headerSubText)
                            .font(.system(size: 12))
                            .foregroundColor(isOffline ? kd.err : kd.trust)
                            .lineLimit(1)
                            .opacity(headerSubText.isEmpty ? 0 : 1)
                    }
                }
            }
        }
        #endif
        // Lifecycle
        .task {
            model.start()
        }
        .onDisappear {
            model.stop()
        }
        // Error/notice toast (simple overlay)
        .onChange(of: model.uiState.error) { _, newError in
            if let err = newError {
                showToast = err
                model.clearError()
            }
        }
        .onChange(of: model.uiState.notice) { _, newNotice in
            if let notice = newNotice {
                showToast = notice
                model.clearNotice()
            }
        }
        // Generic file picker
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            handleFileImportResult(result)
        }
        #if os(iOS)
        // Photo picker (not used directly here; the empty state chip uses its own)
        .onChange(of: photoItems) { _, items in
            Task { await handlePhotoItems(items) }
        }
        #endif
        // Toast overlay
        .overlay(alignment: .top) {
            if let toast = showToast {
                ToastView(message: toast)
                    .onAppear {
                        Task {
                            try? await Task.sleep(for: .seconds(3))
                            showToast = nil
                        }
                    }
            }
        }
    }

    // MARK: - File import handlers

    private func handleFileImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            Task {
                var platformFiles: [Filekit_corePlatformFile] = []
                for url in urls {
                    guard url.startAccessingSecurityScopedResource() else { continue }
                    defer { url.stopAccessingSecurityScopedResource() }
                    do {
                        let tempDir = FileManager.default.temporaryDirectory
                            .appendingPathComponent(UUID().uuidString)
                        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
                        let dest = tempDir.appendingPathComponent(url.lastPathComponent)
                        try FileManager.default.copyItem(at: url, to: dest)
                        let pf = PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
                        platformFiles.append(pf)
                    } catch {}
                }
                if !platformFiles.isEmpty {
                    model.sendFiles(platformFiles)
                }
            }
        case .failure:
            break
        }
    }

    #if os(iOS)
    private func handlePhotoItems(_ items: [PhotosPickerItem]) async {
        var platformFiles: [Filekit_corePlatformFile] = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            let tempDir = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
            guard (try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)) != nil else { continue }
            let dest = tempDir.appendingPathComponent("photo.\(ext)")
            guard (try? data.write(to: dest)) != nil else { continue }
            let pf = PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
            platformFiles.append(pf)
        }
        if !platformFiles.isEmpty {
            model.sendFiles(platformFiles)
        }
    }
    #endif
}

// MARK: - IncomingAuthBannerView (private to chat screen)

/// Pending authorization request inside the chat screen. Renders the same IncomingTransferCardView
/// used by the discovery banner stack, so the accept/reject prompt looks identical everywhere
/// (mirrors the Compose chat, which reuses ReceiveNotification).
private struct IncomingAuthBannerView: View {

    let update: ReceiveMessageUpdate

    var body: some View {
        // Only render for PendingAuthorization status
        let status: ReceiveMessageStatusPendingAuthorization? = {
            switch onEnum(of: update.status) {
            case .pendingAuthorization(let s): return s
            default: return nil
            }
        }()
        guard let status else { return AnyView(EmptyView()) }

        let firstFile = update.messages.compactMap { $0 as? FileMessage }.first
        let isText = firstFile == nil && update.messages.contains { $0 is TextMessage }

        return AnyView(
            IncomingTransferCardView(
                senderName: update.device?.name ?? "this device",
                fileName: firstFile?.fileName,
                fileSize: nil,
                subtitle: isText ? "wants to send you a message" : "wants to send you a file",
                onAccept: { status.acceptTransfer(true) },
                onDecline: { status.acceptTransfer(false) }
            )
        )
    }
}

// MARK: - TransferStatusStripView

/// Slim "something is happening" strip for the transfer phases that carry no percentage —
/// connecting, waiting for the recipient to accept, opening the receive sink. Deliberately not
/// a BannerView: those are ok/warn/err verdicts, this is neutral in-progress chatter.
/// Mirrors: compose-ui/.../chat/DeviceChatScreen.kt  TransferStatusStrip
private struct TransferStatusStripView: View {

    let text: String

    @Environment(\.kdColors) private var kd

    var body: some View {
        HStack(spacing: KdSpacing.s2) {
            ProgressView()
                .progressViewStyle(.circular)
                #if os(iOS)
                .scaleEffect(0.6)
                #else
                .scaleEffect(0.5)
                #endif
                .frame(width: 14, height: 14)

            Text(text)
                .kdStyle(.caption, color: kd.text2)
                .lineLimit(1)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, KdSpacing.s3)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(kd.accent.opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: KdRadii.md, style: .continuous))
    }
}

// MARK: - ChatEmptyStateView

private struct ChatEmptyStateView: View {

    let deviceName: String
    let isOwned: Bool
    let onPickFiles: () -> Void
    let onPickPhotos: () -> Void

    @Environment(\.kdColors) private var kd
    #if os(iOS)
    @State private var photoItems: [PhotosPickerItem] = []
    #endif

    var body: some View {
        VStack(spacing: KdSpacing.gap) {
            DeviceAvatarView(
                kind: .unknown,
                style: isOwned ? .tinted : .neutral,
                size: KdSpacing.heroAvatar
            )

            Text(isOwned ? "Connected to \(deviceName)" : "Send something to \(deviceName)")
                .kdStyle(.headline, color: kd.text)
                .multilineTextAlignment(.center)

            Text(isOwned
                ? "Anything you send here stays in sync across your devices."
                : "Type a message or attach a file to start the conversation."
            )
            .kdStyle(.body, color: kd.text2, multiline: true)
            .multilineTextAlignment(.center)
            .padding(.horizontal, KdSpacing.s7)

            HStack(spacing: KdSpacing.s2) {
                ChipButton(label: "Files", action: onPickFiles)

                #if os(iOS)
                // Photos chip drives a PhotosPicker (iOS only)
                PhotosPicker(
                    selection: $photoItems,
                    maxSelectionCount: 0,
                    matching: .any(of: [.images, .videos])
                ) {
                    Text("Photos")
                        .kdStyle(.body, color: kd.text2)
                        .padding(.horizontal, KdSpacing.s3)
                        .padding(.vertical, KdSpacing.s2)
                        .background(kd.bg1)
                        .clipShape(Capsule())
                }
                .onChange(of: photoItems) { _, items in
                    if !items.isEmpty {
                        onPickPhotos()
                    }
                }
                #else
                // On macOS, Photos chip falls back to fileImporter for images
                ChipButton(label: "Photos", action: onPickFiles)
                #endif

                ChipButton(label: "Text", action: {})
            }
        }
    }
}

private struct ChipButton: View {
    let label: String
    let action: () -> Void
    @Environment(\.kdColors) private var kd

    var body: some View {
        Button(action: action) {
            Text(label)
                .kdStyle(.body, color: kd.text2)
                .padding(.horizontal, KdSpacing.s3)
                .padding(.vertical, KdSpacing.s2)
                .background(kd.bg1)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - MessageListView

/// Scrollable message list — oldest at top, newest at the bottom (natural order).
/// Uses iOS 17's .defaultScrollAnchor(.bottom) + scroll-to-last instead of the
/// fragile double-rotation trick.
/// messages is [ChatMessage] (was [Messages]).
private struct MessageListView: View {

    /// Oldest-first.
    let messages: [ChatMessage]
    let model: ChatModel
    let isOffline: Bool

    @Environment(\.kdColors) private var kd

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                        // Previous (older) message is the one before this in oldest-first order.
                        let older = index > 0 ? messages[index - 1] : nil

                        let showDayDivider = older == nil
                            || ChatTimeFormat.dayKey(older!.timestamp) != ChatTimeFormat.dayKey(message.timestamp)

                        let isFirstOfGroup = older == nil
                            || older!.isSender != message.isSender
                            || message.timestamp - older!.timestamp > groupGapMillis

                        // Day divider sits above the first message of a new day.
                        if showDayDivider {
                            DateChipView(label: ChatTimeFormat.day(message.timestamp))
                                .padding(.vertical, KdSpacing.s3)
                        }

                        MessageRowView(message: message, model: model, isFirstOfGroup: isFirstOfGroup)
                            .padding(.horizontal, KdSpacing.s3)
                            .id(message.id)
                    }
                }
                .padding(.vertical, KdSpacing.s3)
            }
            .scrollContentBackground(.hidden)
            .defaultScrollAnchor(.bottom)
            .opacity(isOffline ? 0.7 : 1.0)
            .onChange(of: messages.count) { _, _ in
                if let lastId = messages.last?.id {
                    withAnimation { proxy.scrollTo(lastId, anchor: .bottom) }
                }
            }
        }
    }
}

private let groupGapMillis: Int64 = 5 * 60 * 1000

// MARK: - ToastView

private struct ToastView: View {
    let message: String
    @Environment(\.kdColors) private var kd

    var body: some View {
        Text(message)
            .kdStyle(.caption, color: kd.text)
            .padding(.horizontal, KdSpacing.s4)
            .padding(.vertical, KdSpacing.s2)
            .background(kd.bg2)
            .clipShape(Capsule())
            .shadow(radius: 4)
            .padding(.top, KdSpacing.s3)
            .transition(.move(edge: .top).combined(with: .opacity))
            .animation(KdMotion.easeOut, value: message)
    }
}

#if os(iOS)

// MARK: - AttachmentChooserView
//
// Gallery / Files / Paste actions plus an inline rail of the user's recent
// photos & videos. Presented as a popover on iPad and (via the system's compact
// adaptation) a bottom sheet on iPhone. Mirrors the Compose AttachmentChooser.

private struct AttachmentChooserView: View {

    let model: ChatModel
    @Binding var isPresented: Bool

    @Environment(\.kdColors) private var kd

    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showFilesImporter = false

    var body: some View {
        VStack(alignment: .leading, spacing: KdSpacing.s3) {
            Text("Add attachment")
                .kdStyle(.headline, color: kd.text)
                .padding(.horizontal, KdSpacing.s4)
                .padding(.top, KdSpacing.s4)

            HStack(spacing: KdSpacing.s2) {
                // Gallery — native photo picker (no permission prompt needed)
                PhotosPicker(
                    selection: $photoItems,
                    maxSelectionCount: 0,
                    matching: .any(of: [.images, .videos])
                ) {
                    AttachmentTile(icon: "photo.on.rectangle", label: "Gallery")
                }
                .buttonStyle(.plain)
                .onChange(of: photoItems) { _, items in
                    guard !items.isEmpty else { return }
                    Task {
                        let files = await AttachmentImport.platformFiles(from: items)
                        if !files.isEmpty { model.sendFiles(files) }
                        isPresented = false
                    }
                }

                // Files — system document picker
                Button { showFilesImporter = true } label: {
                    AttachmentTile(icon: "doc", label: "Files")
                }
                .buttonStyle(.plain)

                // Paste — send clipboard text
                Button {
                    model.pasteFromClipboard()
                    isPresented = false
                } label: {
                    AttachmentTile(icon: "doc.on.clipboard", label: "Paste")
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, KdSpacing.s4)

            // Inline recent-media rail (asks for photo permission on first appear)
            RecentMediaRailView { files in
                if !files.isEmpty { model.sendFiles(files) }
                isPresented = false
            }
            .padding(.bottom, KdSpacing.s4)
        }
        .frame(minWidth: 340, maxWidth: .infinity, alignment: .leading)
        .background(kd.bg1)
        .fileImporter(
            isPresented: $showFilesImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            if case .success(let urls) = result {
                let files = AttachmentImport.platformFiles(fromSecurityScoped: urls)
                if !files.isEmpty { model.sendFiles(files) }
            }
            isPresented = false
        }
        .presentationDetents([.height(300)])
        .presentationDragIndicator(.visible)
        .presentationCompactAdaptation(.sheet)
    }
}

// MARK: - AttachmentTile

private struct AttachmentTile: View {
    let icon: String
    let label: String
    @Environment(\.kdColors) private var kd

    var body: some View {
        VStack(spacing: KdSpacing.s2) {
            Image(systemName: icon)
                .font(.system(size: 22, weight: .regular))
                .foregroundColor(kd.text2)
            Text(label).kdStyle(.body, color: kd.text2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, KdSpacing.s4)
        .background(kd.bg2)
        .clipShape(RoundedRectangle(cornerRadius: KdRadii.md, style: .continuous))
    }
}

// MARK: - RecentMediaRailView

private struct RecentMediaRailView: View {

    let onPick: ([Filekit_corePlatformFile]) -> Void

    @Environment(\.kdColors) private var kd
    @State private var assets: [PHAsset] = []
    @State private var authorized = false

    var body: some View {
        Group {
            if authorized && !assets.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: KdSpacing.s2) {
                        ForEach(assets, id: \.localIdentifier) { asset in
                            RecentThumb(asset: asset) {
                                Task {
                                    let pf = await AttachmentImport.platformFile(from: asset)
                                    onPick(pf.map { [$0] } ?? [])
                                }
                            }
                        }
                    }
                    .padding(.horizontal, KdSpacing.s4)
                }
                .frame(height: 76)
            } else {
                EmptyView()
            }
        }
        .task { await load() }
    }

    private func load() async {
        let status = await requestAuthorization()
        guard status == .authorized || status == .limited else {
            authorized = false
            return
        }
        authorized = true

        let options = PHFetchOptions()
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        options.fetchLimit = 40
        options.predicate = NSPredicate(
            format: "mediaType == %d || mediaType == %d",
            PHAssetMediaType.image.rawValue,
            PHAssetMediaType.video.rawValue
        )
        let result = PHAsset.fetchAssets(with: options)
        var list: [PHAsset] = []
        result.enumerateObjects { obj, _, _ in list.append(obj) }
        assets = list
    }

    private func requestAuthorization() async -> PHAuthorizationStatus {
        let current = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if current != .notDetermined { return current }
        return await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { cont.resume(returning: $0) }
        }
    }
}

// MARK: - RecentThumb

private struct RecentThumb: View {
    let asset: PHAsset
    let onTap: () -> Void

    @Environment(\.kdColors) private var kd
    @State private var image: UIImage?

    var body: some View {
        Button(action: onTap) {
            ZStack(alignment: .bottomLeading) {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    kd.bg2
                }
                if asset.mediaType == .video {
                    Image(systemName: "play.fill")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.white)
                        .padding(4)
                }
            }
            .frame(width: 76, height: 76)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: KdRadii.sm, style: .continuous))
        }
        .buttonStyle(.plain)
        .task { await loadThumb() }
    }

    private func loadThumb() async {
        let manager = PHImageManager.default()
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .fast
        options.isNetworkAccessAllowed = true
        let target = CGSize(width: 160, height: 160)
        image = await withCheckedContinuation { (cont: CheckedContinuation<UIImage?, Never>) in
            manager.requestImage(
                for: asset,
                targetSize: target,
                contentMode: .aspectFill,
                options: options
            ) { img, _ in
                cont.resume(returning: img)
            }
        }
    }
}

// MARK: - AttachmentImport
//
// Shared helpers that turn picked sources into FileKit PlatformFiles the shared
// KMP layer can send. Everything is copied into a temp sandbox dir first.

private enum AttachmentImport {

    static func platformFiles(fromSecurityScoped urls: [URL]) -> [Filekit_corePlatformFile] {
        var out: [Filekit_corePlatformFile] = []
        for url in urls {
            guard url.startAccessingSecurityScopedResource() else { continue }
            defer { url.stopAccessingSecurityScopedResource() }
            if let pf = copyToTemp(from: url) { out.append(pf) }
        }
        return out
    }

    static func platformFiles(from items: [PhotosPickerItem]) async -> [Filekit_corePlatformFile] {
        var out: [Filekit_corePlatformFile] = []
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
            let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
            if let pf = writeTemp(data: data, filename: "photo.\(ext)") { out.append(pf) }
        }
        return out
    }

    static func platformFile(from asset: PHAsset) async -> Filekit_corePlatformFile? {
        let resources = PHAssetResource.assetResources(for: asset)
        let preferred: [PHAssetResourceType] = [.photo, .video, .fullSizePhoto, .fullSizeVideo, .pairedVideo]
        let resource = preferred.compactMap { type in resources.first { $0.type == type } }.first
            ?? resources.first
        guard let resource else { return nil }

        guard let dir = makeTempDir() else { return nil }
        let dest = dir.appendingPathComponent(resource.originalFilename)
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        do {
            try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                PHAssetResourceManager.default().writeData(for: resource, toFile: dest, options: options) { error in
                    if let error { cont.resume(throwing: error) } else { cont.resume() }
                }
            }
        } catch {
            return nil
        }
        return PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
    }

    private static func copyToTemp(from url: URL) -> Filekit_corePlatformFile? {
        guard let dir = makeTempDir() else { return nil }
        let dest = dir.appendingPathComponent(url.lastPathComponent)
        guard (try? FileManager.default.copyItem(at: url, to: dest)) != nil else { return nil }
        return PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
    }

    private static func writeTemp(data: Data, filename: String) -> Filekit_corePlatformFile? {
        guard let dir = makeTempDir() else { return nil }
        let dest = dir.appendingPathComponent(filename)
        guard (try? data.write(to: dest)) != nil else { return nil }
        return PlatformFileBridgeKt.platformFileFromPath(path: dest.path)
    }

    private static func makeTempDir() -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        guard (try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)) != nil else {
            return nil
        }
        return dir
    }
}

#endif
