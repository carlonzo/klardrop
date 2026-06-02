import SwiftUI
import Observation
import presentation

// ---------------------------------------------------------------------------
// DiscoveryAppModel — iOS 17 @Observable wrapper over DiscoveryController.
//
// Replaces the Phase 1B-1 ObservableObject/@Published DiscoveryModel.
//
// Ownership rule: the single DiscoveryController is created ONCE here via
// bootstrap.discoveryController() and held for the process lifetime.
// Never call discoveryController() more than once — each call constructs a
// fresh (and divergent) instance with its own coroutine scope.
//
// StateFlow bridging: each stored property is seeded synchronously in init()
// from flow.value (prevents the empty-flash on first render), then start()
// opens a Task that iterates `for await v in flow { self.field = v }`.
// Because this class is @MainActor, all assignments are on the main thread.
// stop() cancels the Tasks which tears down the Kotlin SKIE collectors.
//
// Callers drive lifecycle with `.task { model.start() }` (auto-cancels on
// disappear) plus an explicit stop() / onDisappear for onDispose.
// ---------------------------------------------------------------------------

@Observable @MainActor
final class DiscoveryAppModel {

    // MARK: - Kotlin-side handles (singletons)

    /// Stored so KlardropNav can create ChatModel instances (deviceChatViewModel).
    /// Used ONLY for that purpose; discoveryController / updateBannerController
    /// are each called exactly once below.
    let bootstrap: KlardropBootstrap
    let controller: DiscoveryController
    let updateController: UpdateBannerController

    // MARK: - Observable state (seeded from StateFlow.value in init)

    private(set) var state: DiscoveryScreenState
    private(set) var permissionsState: PermissionsState
    private(set) var backgroundDiscoveryEnabled: Bool
    private(set) var updateStatus: UpdateStatus
    private(set) var updateInstallProgress: InstallProgress

    // MARK: - Navigation state

    /// Compact (iPhone) push stack.
    var path: [ChatRoute] = []
    /// Regular (iPad) sidebar selection.
    var selectedChat: ChatRoute?

    // MARK: - Tasks

    private var stateTasks: [Task<Void, Never>] = []

    // MARK: - Init

    init(bootstrap: KlardropBootstrap) {
        self.bootstrap = bootstrap
        let ctrl = bootstrap.discoveryController()
        let updateCtrl = bootstrap.updateBannerController()
        self.controller = ctrl
        self.updateController = updateCtrl

        // Seed synchronously — no empty-flash on first render.
        self.state = ctrl.screenStateFlow.value
        self.permissionsState = ctrl.permissionsState.value
        self.backgroundDiscoveryEnabled = ctrl.backgroundDiscoveryEnabled.value.boolValue
        self.updateStatus = updateCtrl.status.value
        self.updateInstallProgress = updateCtrl.installProgress.value
    }

    // MARK: - Lifecycle

    func start() {
        guard stateTasks.isEmpty else { return }

        stateTasks = [
            Task { [weak self] in
                guard let self else { return }
                for await next in self.controller.screenStateFlow {
                    self.state = next
                }
            },
            Task { [weak self] in
                guard let self else { return }
                for await next in self.controller.permissionsState {
                    self.permissionsState = next
                }
            },
            Task { [weak self] in
                guard let self else { return }
                for await next in self.controller.backgroundDiscoveryEnabled {
                    self.backgroundDiscoveryEnabled = next.boolValue
                }
            },
            Task { [weak self] in
                guard let self else { return }
                for await next in self.updateController.status {
                    self.updateStatus = next
                }
            },
            Task { [weak self] in
                guard let self else { return }
                for await next in self.updateController.installProgress {
                    self.updateInstallProgress = next
                }
            },
        ]
    }

    func stop() {
        stateTasks.forEach { $0.cancel() }
        stateTasks = []
    }

    // MARK: - Navigation helpers

    func navigateToChat(_ route: ChatRoute) {
        path.append(route)
        setActiveChatDeviceId(route.deviceId)
    }

    func popChat() {
        if !path.isEmpty { path.removeLast() }
        setActiveChatDeviceId(nil)
    }

    // MARK: - Pass-through intents (synchronous controller calls on main)

    func onDeviceTap(_ device: DeviceUi) {
        controller.onDeviceClick(deviceUi: device)
    }

    func addToTrusted(_ device: DeviceUi) {
        controller.onAddToTrusted(deviceUi: device)
    }

    func forgetDevice(_ device: DeviceUi) {
        controller.onForgetDevice(deviceUi: device)
    }

    func onSendData(_ device: DeviceUi, _ data: OnDataToSend) {
        controller.onSendData(deviceUi: device, onDataToSend: data)
    }

    func saveCustomDeviceName(_ name: String?) {
        controller.saveCustomDeviceName(customName: name)
    }

    func setBackgroundDiscoveryEnabled(_ enabled: Bool) {
        controller.setBackgroundDiscoveryEnabled(enabled: enabled)
    }

    func setActiveChatDeviceId(_ deviceId: String?) {
        controller.setActiveChatDeviceId(deviceId: deviceId)
    }

    func dismissPairing() {
        controller.dismissPairingDialog()
    }

    func onReceivedCardClicked(_ update: ReceiveMessageUpdate) {
        controller.onReceivedCardClicked(receiveUpdate: update)
    }

    func onCardDismissed(_ id: Int32) {
        controller.onCardDismissed(id: id)
    }

    func onNotificationDismissed(_ id: Int32) {
        controller.onNotificationDismissed(notificationId: id)
    }

    func onNotificationPair(_ id: Int32) {
        controller.onNotificationPair(notificationId: id)
    }

    func onConnectionInfoAccepted(_ msg: ConnectionInfoMessage) {
        controller.onConnectionInfoAccepted(message: msg)
    }

    func onRequestCapability(_ capability: Capability) {
        // TODO: wire through when DiscoveryController exposes onRequestCapability
    }

    func updateAction(_ action: UpdateAction) -> Bool {
        return updateController.onAction(action: action)
    }

    func updateRestart() {
        updateController.onRestart()
    }
}
