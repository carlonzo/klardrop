import presentation
import KlardropCommon
import Foundation

// Typealiases bringing Kotlin swift-export declarations into app-level scope
// across both iOS and macOS targets.

// FileKit
public typealias PlatformFile = ExportedKotlinPackages.io.github.vinceglb.filekit.PlatformFile
public typealias Filekit_corePlatformFile = PlatformFile

// Presentation root (:presentation)
public typealias ActivityState = ExportedKotlinPackages.com.carlom.klardrop.ActivityState
public typealias DeviceUi = ExportedKotlinPackages.com.carlom.klardrop.DeviceUi
public typealias DiscoveryController = ExportedKotlinPackages.com.carlom.klardrop.DiscoveryController
public typealias DiscoveryScreenState = ExportedKotlinPackages.com.carlom.klardrop.DiscoveryScreenState
public typealias KlardropBootstrap = ExportedKotlinPackages.com.carlom.klardrop.KlardropBootstrap
public typealias OnDataToSend = ExportedKotlinPackages.com.carlom.klardrop.OnDataToSend
public typealias OnDeviceActionListener = ExportedKotlinPackages.com.carlom.klardrop.OnDeviceActionListener
public typealias OutgoingTransfer = ExportedKotlinPackages.com.carlom.klardrop.OutgoingTransfer
public typealias PairingDialogState = ExportedKotlinPackages.com.carlom.klardrop.PairingDialogState
public typealias PreparedFile = ExportedKotlinPackages.com.carlom.klardrop.PreparedFile
public typealias ReceiveNotificationsCallbacks = ExportedKotlinPackages.com.carlom.klardrop.ReceiveNotificationsCallbacks
public typealias ShowDevicesControllerHelper = ExportedKotlinPackages.com.carlom.klardrop.ShowDevicesControllerHelper
public typealias TrustStatus = ExportedKotlinPackages.com.carlom.klardrop.TrustStatus
public typealias UiDependencies = ExportedKotlinPackages.com.carlom.klardrop.UiDependencies
public typealias UiNotification = ExportedKotlinPackages.com.carlom.klardrop.UiNotification
public typealias UpdateBannerController = ExportedKotlinPackages.com.carlom.klardrop.UpdateBannerController

// Chat (:presentation:chat)
public typealias ChatUiState = ExportedKotlinPackages.com.carlom.klardrop.chat.ChatUiState
public typealias DeviceChatViewModel = ExportedKotlinPackages.com.carlom.klardrop.chat.DeviceChatViewModel
public typealias TransferStats = ExportedKotlinPackages.com.carlom.klardrop.chat.TransferStats

// Common (:klardrop-common)
public typealias Klardrop = ExportedKotlinPackages.com.carlom.klardrop.common.Klardrop
public typealias CommonComponent = ExportedKotlinPackages.com.carlom.klardrop.common.di.CommonComponent

// Communication & Messages
public typealias Reachability = ExportedKotlinPackages.com.carlom.klardrop.common.communication.Reachability
public typealias MessengerSendProgress = ExportedKotlinPackages.com.carlom.klardrop.common.communication.MessengerSendProgress
public typealias ConnectionInfoMessage = ExportedKotlinPackages.com.carlom.klardrop.common.communication.message.ConnectionInfoMessage
public typealias FileMessage = ExportedKotlinPackages.com.carlom.klardrop.common.communication.message.FileMessage
public typealias TextMessage = ExportedKotlinPackages.com.carlom.klardrop.common.communication.message.TextMessage

// Receiver
public typealias ReceiveMessageStatus = ExportedKotlinPackages.com.carlom.klardrop.common.receiver.ReceiveMessageStatus
public typealias ReceiveMessageUpdate = ExportedKotlinPackages.com.carlom.klardrop.common.receiver.ReceiveMessageUpdate
public typealias MessageReceiver = ExportedKotlinPackages.com.carlom.klardrop.common.receiver.MessageReceiver
public typealias MessageProgress = ExportedKotlinPackages.com.carlom.klardrop.common.receiver.MessageProgress

// Persistence & Database
public typealias ChatMessage = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.ChatMessage
public typealias MessageRepository = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.MessageRepository
public typealias SendStatus = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.SendStatus
public typealias KlardropProperties = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.KlardropProperties
public typealias KnownDevicesRepository = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.KnownDevicesRepository
public typealias LocalPropertiesRepository = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.LocalPropertiesRepository
public typealias File_transfers = ExportedKotlinPackages.com.carlom.klardrop.common.database.File_transfers
public typealias Messages = ExportedKotlinPackages.com.carlom.klardrop.common.database.Messages

// Update
public typealias UpdateStatus = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateStatus
public typealias UpdateAction = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateAction
public typealias InstallProgress = ExportedKotlinPackages.com.carlom.klardrop.common.update.InstallProgress
public typealias InstallChannel = ExportedKotlinPackages.com.carlom.klardrop.common.update.InstallChannel
public typealias UpdateChecker = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateChecker
public typealias UpdateInstaller = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateInstaller
public typealias UpdateManifestFetcher = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateManifestFetcher
public typealias LatestManifest = ExportedKotlinPackages.com.carlom.klardrop.common.update.LatestManifest
public typealias ReleaseAsset = ExportedKotlinPackages.com.carlom.klardrop.common.update.ReleaseAsset

// QR Share
public typealias QrSharePayload = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrSharePayload
public typealias QrShareState = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrShareState
public typealias QrDownloadProgress = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrDownloadProgress
public typealias QrShareSession = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrShareSession
public typealias SharedFile = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.SharedFile

// Permissions
public typealias PermissionsState = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.PermissionsState
public typealias PermissionsMonitor = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.PermissionsMonitor
public typealias Capability = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.Capability
public typealias CapabilityStatus = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.CapabilityStatus
public typealias EducationalNote = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.EducationalNote

// Share
public typealias ShareSheetDismissTrigger = ExportedKotlinPackages.com.carlom.klardrop.common.share.ShareSheetDismissTrigger
public typealias ShareSheetDismissPolicy = ExportedKotlinPackages.com.carlom.klardrop.common.share.ShareSheetDismissPolicy

// Trust
public typealias PairingApprovalCallback = ExportedKotlinPackages.com.carlom.klardrop.common.trust.PairingApprovalCallback
public typealias TrustStorage = ExportedKotlinPackages.com.carlom.klardrop.common.trust.TrustStorage
public typealias InMemoryTrustStorage = ExportedKotlinPackages.com.carlom.klardrop.common.trust.InMemoryTrustStorage
public typealias PairingProtocolCoordinator = ExportedKotlinPackages.com.carlom.klardrop.common.trust.PairingProtocolCoordinator
public typealias TrustCrypto = ExportedKotlinPackages.com.carlom.klardrop.common.trust.TrustCrypto
public typealias TrustManager = ExportedKotlinPackages.com.carlom.klardrop.common.trust.TrustManager
public typealias TrustedDevice = ExportedKotlinPackages.com.carlom.klardrop.common.trust.TrustedDevice

// Notifications
public typealias NotificationAction = ExportedKotlinPackages.com.carlom.klardrop.common.notifications.NotificationAction
public typealias Notifier = ExportedKotlinPackages.com.carlom.klardrop.common.notifications.Notifier

// Utils
public typealias DeviceType = ExportedKotlinPackages.com.carlom.klardrop.common.utils.DeviceType
public typealias OsType = ExportedKotlinPackages.com.carlom.klardrop.common.utils.OsType
public typealias Coroutines = ExportedKotlinPackages.com.carlom.klardrop.common.utils.Coroutines
public typealias PlatformFileSystem = ExportedKotlinPackages.com.carlom.klardrop.common.utils.PlatformFileSystem
public typealias FileTypeUtils = ExportedKotlinPackages.com.carlom.klardrop.common.utils.FileTypeUtils
public typealias PreparedSendFile = ExportedKotlinPackages.com.carlom.klardrop.common.utils.PreparedSendFile
public typealias ResolvedFileData = ExportedKotlinPackages.com.carlom.klardrop.common.utils.ResolvedFileData

// Crash reporting
public typealias CrashReporter = ExportedKotlinPackages.com.klardrop.common.CrashReporter
public typealias ReportOutcome = ExportedKotlinPackages.com.klardrop.common.ReportOutcome

// macOS control plane
#if os(macOS)
public typealias ControlPlane = ExportedKotlinPackages.com.carlom.klardrop.control.ControlPlane
public typealias ControlPlaneService = ExportedKotlinPackages.com.carlom.klardrop.control.ControlPlaneService
#endif

// Free functions bridging top-level Kotlin functions
public func platformFileFromPath(path: String) -> PlatformFile {
    ExportedKotlinPackages.com.carlom.klardrop.platformFileFromPath(path: path)
}

public func sharedFileFromPlatformFile(file: PlatformFile) -> SharedFile {
    ExportedKotlinPackages.com.carlom.klardrop.sharedFileFromPlatformFile(file: file)
}

public func openableUrlOrNull(text: String) -> String? {
    ExportedKotlinPackages.com.carlom.klardrop.chat.openableUrlOrNull(text: text)
}


// Nested Kotlin types exposed under their parent Swift namespace
extension Reachability {
    public typealias Unknown = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_communication_Reachability_Unknown
    public typealias Reachable = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_communication_Reachability_Reachable
    public typealias Probing = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_communication_Reachability_Probing
    public typealias Unreachable = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_communication_Reachability_Unreachable
}

extension UpdateStatus {
    public typealias Available = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Available
    public typealias Checking = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Checking
    public typealias Failed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Failed
    public typealias Unknown = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Unknown
    public typealias UpToDate = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_UpToDate
}

extension ReceiveMessageStatus {
    public typealias PendingAuthorization = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_PendingAuthorization
    public typealias Completed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Completed
    public typealias Failed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Failed
    public typealias Progress = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Progress
    public typealias Started = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Started
}

extension Capability {
    public static let localNetwork = Capability.LOCAL_NETWORK
    public static let bluetooth = Capability.BLUETOOTH
    public static let notifications = Capability.NOTIFICATIONS
    public static let nearbyWifiDevices = Capability.NEARBY_WIFI_DEVICES
    public static let location = Capability.LOCATION

    public var name: String { description }
}

extension CapabilityStatus {
    public static let granted = CapabilityStatus.Granted
    public static let denied = CapabilityStatus.Denied
    public static let unknown = CapabilityStatus.Unknown
    public static let notApplicable = CapabilityStatus.NotApplicable
}


extension DeviceType {
    public static let mobile = DeviceType.MOBILE
    public static let desktop = DeviceType.DESKTOP
    public static let unknown = DeviceType.UNKNOWN
}

extension QrSharePayload {
    public typealias Files = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_qrshare_QrSharePayload_Files
    public typealias Text = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_qrshare_QrSharePayload_Text
}
public typealias QrSharePayloadFiles = QrSharePayload.Files

extension ShareSheetDismissTrigger {
    public static let selectDevice = ShareSheetDismissTrigger.SelectDevice
    public static let sendTap = ShareSheetDismissTrigger.SendTap
    public static let swipeAway = ShareSheetDismissTrigger.SwipeAway
    public static let completed = ShareSheetDismissTrigger.Completed
    public static let userHide = ShareSheetDismissTrigger.UserHide
    public static let emptyPayload = ShareSheetDismissTrigger.EmptyPayload
}



