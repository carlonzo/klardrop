import SwiftUI
import PhotosUI
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
// On iPhone: pushed in NavigationStack with toolbar header.
// On iPad: rendered in split detail; ChatHeaderView placed above by KlardropNav.
// ---------------------------------------------------------------------------

struct DeviceChatScreen: View {

    @State var model: ChatModel
    let deviceName: String
    let isOwned: Bool

    @Environment(\.kdColors) private var kd
    @Environment(\.dismiss) private var dismiss

    // File picking state
    @State private var showFilePicker = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showToast: String? = nil

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

    // Sorted messages (newest first for reversed List, matches Kotlin sortedByDescending)
    private var sortedMessages: [Messages] {
        model.messages.sorted { $0.timestamp > $1.timestamp }
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            kd.bg0.ignoresSafeArea()

            VStack(spacing: 0) {
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
                    onAttach: { showFilePicker = true },
                    enabled: !isOffline
                )
                .background(kd.bg0)
                .padding(.bottom, KdSpacing.s1)
            }
        }
        // Navigation bar (iPhone): principal toolbar item with compact ChatHeaderView
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                ChatHeaderView(
                    deviceName: deviceName,
                    subText: headerSubText,
                    kind: .unknown,
                    avatarStyle: headerAvatarStyle,
                    status: headerStatus,
                    isReachable: !isOffline,
                    avatarSize: 28
                )
                // Remove the divider from the toolbar variant — NavigationBar handles it
                .overlay(alignment: .bottom) { Color.clear.frame(height: 0) }
            }
        }
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
        // Photo picker (not used directly here; the empty state chip uses its own)
        .onChange(of: photoItems) { _, items in
            Task { await handlePhotoItems(items) }
        }
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
}

// MARK: - IncomingAuthBannerView (private to chat screen)

/// Warn-tone banner for a pending authorization request inside the chat screen.
private struct IncomingAuthBannerView: View {

    let update: ReceiveMessageUpdate

    @Environment(\.kdColors) private var kd

    var body: some View {
        // Only render for PendingAuthorization status
        let status: ReceiveMessageStatusPendingAuthorization? = {
            switch onEnum(of: update.status) {
            case .pendingAuthorization(let s): return s
            default: return nil
            }
        }()
        guard let status else { return AnyView(EmptyView()) }

        let sender = update.device?.name ?? "this device"
        let count = update.messages.count
        let title = "\(sender) wants to send you \(count == 1 ? "an item" : "\(count) items")"

        return AnyView(
            BannerView(tone: .warn, title: title) {
                Button("Reject") { status.acceptTransfer(KotlinBoolean(value: false)) }
                    .kdStyle(.body, color: kd.text2)
                    .buttonStyle(.plain)
                Button("Accept") { status.acceptTransfer(KotlinBoolean(value: true)) }
                    .kdStyle(.body, color: kd.accent)
                    .buttonStyle(.plain)
            }
        )
    }
}

// MARK: - ChatEmptyStateView

private struct ChatEmptyStateView: View {

    let deviceName: String
    let isOwned: Bool
    let onPickFiles: () -> Void
    let onPickPhotos: () -> Void

    @Environment(\.kdColors) private var kd
    @State private var photoItems: [PhotosPickerItem] = []

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

                // Photos chip drives a PhotosPicker
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

/// Reversed-layout scrollable message list (newest at bottom).
private struct MessageListView: View {

    let messages: [Messages]
    let model: ChatModel
    let isOffline: Bool

    @Environment(\.kdColors) private var kd

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    // Reversed: we iterate from index 0 (newest) to end (oldest)
                    // and display in reverse so newest appears at screen bottom.
                    ForEach(Array(messages.enumerated()), id: \.element.id) { index, message in
                        let older = messages.indices.contains(index + 1) ? messages[index + 1] : nil

                        let isFirstOfGroup = older == nil
                            || older!.is_sender != message.is_sender
                            || message.timestamp - older!.timestamp > groupGapMillis

                        let showDayDivider = older == nil
                            || ChatTimeFormat.dayKey(older!.timestamp) != ChatTimeFormat.dayKey(message.timestamp)

                        MessageRowView(message: message, model: model, isFirstOfGroup: isFirstOfGroup)
                            .padding(.horizontal, KdSpacing.s3)

                        if showDayDivider {
                            DateChipView(label: ChatTimeFormat.day(message.timestamp))
                                .padding(.vertical, KdSpacing.s3)
                        }
                    }
                }
                .padding(.vertical, KdSpacing.s3)
                // Rotate the VStack so the content appears bottom-up
                .rotationEffect(.degrees(180))
            }
            // Rotate the ScrollView so newest messages are at the bottom
            .rotationEffect(.degrees(180))
            .scrollContentBackground(.hidden)
            .opacity(isOffline ? 0.7 : 1.0)
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
