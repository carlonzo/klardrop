import SwiftUI
import Observation
import presentation

// ---------------------------------------------------------------------------
// ChatModel — @Observable wrapper over DeviceChatViewModel.
//
// Lifecycle:
//   - Created when a chat screen opens (held as @State in DeviceChatScreen).
//   - start() opens for-await Tasks on the Kotlin StateFlows.
//   - stop() cancels Tasks and calls viewModel.onDispose().
//   - .task { model.start() } preferred driver (auto-cancels on disappear).
//
// StateFlow bridging:
//   - messages: StateFlow<List<Messages>> -> iterated via for-await, each
//     element cast to NSArray then flattened to [Messages] via compactMap.
//   - uiState/reachability: typed SKIE StateFlow -> for-await, direct cast.
//   - pendingAuth: Optional StateFlow -> for-await.
//
// Note: DeviceChatViewModel.copyText is exposed as doCopyText(text:) in
// Swift (SKIE renames to avoid Swift keyword conflict).
// ---------------------------------------------------------------------------

@Observable @MainActor
final class ChatModel {

    // MARK: - Observable state

    private(set) var messages: [Messages] = []
    private(set) var uiState: ChatUiState
    private(set) var pendingAuth: ReceiveMessageUpdate? = nil
    private(set) var reachability: Reachability

    /// Draft text owned here so MessageInputView binds via @Bindable.
    var draft: String = ""

    // MARK: - Kotlin VM handle (used by MessageRowView for per-file-transfer flows)

    let viewModel: DeviceChatViewModel

    // MARK: - Tasks

    private var tasks: [Task<Void, Never>] = []

    // MARK: - Init

    init(deviceId: String, bootstrap: KlardropBootstrap) {
        let vm = bootstrap.deviceChatViewModel(deviceId: deviceId)
        self.viewModel = vm

        // Seed synchronously from current StateFlow values.
        self.uiState = vm.uiState.value as? ChatUiState ?? ChatUiState(error: nil, notice: nil)
        self.reachability = vm.reachability.value as? Reachability ?? ReachabilityUnknown()
        self.pendingAuth = vm.pendingAuth.value as? ReceiveMessageUpdate
        if let list = vm.messages.value as? [Messages] {
            self.messages = list
        } else if let arr = vm.messages.value as? NSArray {
            self.messages = arr.compactMap { $0 as? Messages }
        }
    }

    // MARK: - Lifecycle

    func start() {
        guard tasks.isEmpty else { return }

        tasks = [
            // messages: StateFlow<List<Messages>> — emits Kotlin List, bridged as NSArray
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.messages {
                    if let list = next as? [Messages] {
                        self.messages = list
                    } else if let arr = next as? NSArray {
                        self.messages = arr.compactMap { $0 as? Messages }
                    }
                }
            },
            // uiState: StateFlow<ChatUiState>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.uiState {
                    if let s = next as? ChatUiState {
                        self.uiState = s
                    }
                }
            },
            // reachability: StateFlow<Reachability>
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.reachability {
                    if let r = next as? Reachability {
                        self.reachability = r
                    }
                }
            },
            // pendingAuth: StateFlow<ReceiveMessageUpdate?> — optional StateFlow
            Task { [weak self] in
                guard let self else { return }
                for await next in self.viewModel.pendingAuth {
                    // next is ReceiveMessageUpdate? (already optional via SKIE optional StateFlow)
                    self.pendingAuth = next as? ReceiveMessageUpdate
                }
            },
        ]
    }

    func stop() {
        tasks.forEach { $0.cancel() }
        tasks = []
        viewModel.onDispose()
    }

    // MARK: - Intent pass-throughs

    func send() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        viewModel.sendTextMessage(text: trimmed)
        draft = ""
    }

    func sendFiles(_ files: [Filekit_corePlatformFile]) {
        viewModel.sendFiles(files: files)
    }

    func copyText(_ text: String) {
        // SKIE renames copyText -> doCopyText to avoid Swift keyword collision
        viewModel.doCopyText(text: text)
    }

    func openFile(_ path: String) {
        viewModel.openFileClicked(filePath: path)
    }

    func openUrl(_ url: String) {
        viewModel.openUrlClicked(url: url)
    }

    func retryFile(_ fileTransferId: Int64) {
        viewModel.retryFileTransfer(failedFileTransferId: fileTransferId)
    }

    func clearError() {
        viewModel.clearError()
    }

    func clearNotice() {
        viewModel.clearNotice()
    }
}
