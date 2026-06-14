import SwiftUI
import presentation

// ---------------------------------------------------------------------------
// ShareInboxSheet — host-side device picker shown after another app shares
// files into Klardrop (via the Share Extension → klardrop://share hand-off).
//
// Reuses the existing ShareSheetView UI and the same send path as the rest of
// the app: model.onSendData(device, .FilesList(files)). The incoming files have
// already been bridged to PlatformFile by RootView before presentation.
// ---------------------------------------------------------------------------

struct ShareInboxSheet: View {

    let model: DiscoveryAppModel
    let files: [Filekit_corePlatformFile]
    let onComplete: () -> Void

    @State private var selectedId: String?

    private var devices: [DeviceUi] { model.state.devices }

    private var trusted: [KdShareDevice] {
        devices.filter { $0.isTrustedForShare }.map { $0.asShareDevice }
    }

    private var nearby: [KdShareDevice] {
        devices.filter { !$0.isTrustedForShare }.map { $0.asShareDevice }
    }

    var body: some View {
        ShareSheetView(
            trustedDevices: trusted,
            nearbyDevices: nearby,
            selectedId: $selectedId,
            onSend: { share in
                guard let share,
                      let device = devices.first(where: { $0.deviceId == share.id })
                else { return }
                // SKIE flattens the Kotlin nested class OnDataToSend.FilesList to
                // the top-level Swift type OnDataToSendFilesList.
                model.onSendData(device, OnDataToSendFilesList(files: files))
                onComplete()
            }
        )
    }
}

// MARK: - DeviceUi → KdShareDevice

extension DeviceUi {

    var isTrustedForShare: Bool {
        if case .trusted = onEnum(of: trustStatus) { return true }
        return false
    }

    var asShareDevice: KdShareDevice {
        KdShareDevice(
            id: deviceId,
            name: deviceName,
            kind: deviceKind,
            isTrusted: isTrustedForShare,
            status: reachabilityStatus
        )
    }
}
