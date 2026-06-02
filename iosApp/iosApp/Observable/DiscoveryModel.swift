import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// DiscoveryModel — ObservableObject wrapper bridging DiscoveryController's
// SKIE SkieSwiftMutableStateFlow<DiscoveryScreenState> into SwiftUI.
//
// Deployment floor: iOS 14.1 — @Observable (iOS 17+) is NOT used.
// Pattern: ObservableObject + @Published + Task-based for-await collection.
//
// Lifecycle:
//   - init(bootstrap:) seeds @Published state synchronously from .value to
//     prevent an initial empty render flash.
//   - start() opens the async iteration; stop() cancels it (and the SKIE
//     iterator cancels the Kotlin collector in deinit/cancellation).
//   - Pair .task { model.start() } with .onDisappear { model.stop() } in
//     the owning view for automatic lifecycle management.
// ---------------------------------------------------------------------------

@MainActor
final class DiscoveryModel: ObservableObject {

    private let controller: DiscoveryController
    @Published private(set) var state: DiscoveryScreenState
    private var stateTask: Task<Void, Never>?

    init(bootstrap: KlardropBootstrap) {
        // discoveryController() creates a fresh instance from CommonComponent;
        // hold it here so the same instance is used for the lifetime of this model.
        self.controller = bootstrap.discoveryController()
        // Seed synchronously from .value — no empty flash on first render.
        self.state = controller.screenStateFlow.value
    }

    // MARK: - Subscription lifecycle

    func start() {
        guard stateTask == nil else { return }
        stateTask = Task { [weak self] in
            guard let self else { return }
            // SkieSwiftMutableStateFlow conforms to AsyncSequence (via SkieSwiftFlowProtocol).
            // The iterator cancels the Kotlin collector in deinit / on Swift task cancellation.
            for await next in self.controller.screenStateFlow {
                // Task is @MainActor; assignment is always on the main thread.
                self.state = next
            }
        }
    }

    func stop() {
        stateTask?.cancel()
        stateTask = nil
    }

    // MARK: - Pass-through intents (synchronous controller methods)

    func onDeviceTap(_ device: DeviceUi) {
        controller.onDeviceClick(deviceUi: device)
    }

    func addToTrusted(_ device: DeviceUi) {
        controller.onAddToTrusted(deviceUi: device)
    }

    func forgetDevice(_ device: DeviceUi) {
        controller.onForgetDevice(deviceUi: device)
    }
}
