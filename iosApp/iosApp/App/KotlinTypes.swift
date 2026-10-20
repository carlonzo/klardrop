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
public typealias PairingDialogState = ExportedKotlinPackages.com.carlom.klardrop.PairingDialogState
public typealias TrustStatus = ExportedKotlinPackages.com.carlom.klardrop.TrustStatus
public typealias UiNotification = ExportedKotlinPackages.com.carlom.klardrop.UiNotification
public typealias UpdateBannerController = ExportedKotlinPackages.com.carlom.klardrop.UpdateBannerController

// Chat (:presentation:chat)
public typealias ChatUiState = ExportedKotlinPackages.com.carlom.klardrop.chat.ChatUiState
public typealias DeviceChatViewModel = ExportedKotlinPackages.com.carlom.klardrop.chat.DeviceChatViewModel

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

// Persistence & Database
public typealias ChatMessage = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.ChatMessage
public typealias MessageRepository = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.MessageRepository
public typealias SendStatus = ExportedKotlinPackages.com.carlom.klardrop.common.persistence.SendStatus
public typealias File_transfers = ExportedKotlinPackages.com.carlom.klardrop.common.database.File_transfers
public typealias Messages = ExportedKotlinPackages.com.carlom.klardrop.common.database.Messages

// Update
public typealias UpdateStatus = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateStatus
public typealias UpdateAction = ExportedKotlinPackages.com.carlom.klardrop.common.update.UpdateAction
public typealias InstallProgress = ExportedKotlinPackages.com.carlom.klardrop.common.update.InstallProgress

// QR Share
public typealias QrSharePayload = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrSharePayload
public typealias QrShareState = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrShareState
public typealias QrDownloadProgress = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrDownloadProgress
public typealias QrShareSession = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.QrShareSession
public typealias SharedFile = ExportedKotlinPackages.com.carlom.klardrop.common.qrshare.SharedFile

// Permissions
public typealias PermissionsState = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.PermissionsState
public typealias CapabilityGrant = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.CapabilityGrant
public typealias Capability = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.Capability
public typealias CapabilityStatus = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.CapabilityStatus
public typealias EducationalNote = ExportedKotlinPackages.com.carlom.klardrop.common.permissions.EducationalNote
public typealias ConnectivityRestrictions = ExportedKotlinPackages.com.carlom.klardrop.common.connectivity.ConnectivityRestrictions
public typealias ConnectivityRestriction = ExportedKotlinPackages.com.carlom.klardrop.common.connectivity.ConnectivityRestriction
public typealias ConnectivityRestrictionReason = ExportedKotlinPackages.com.carlom.klardrop.common.connectivity.ConnectivityRestrictionReason
public typealias DeviceConnectionKind = ExportedKotlinPackages.com.carlom.klardrop.DeviceConnectionKind
// Nested inside the sealed DeviceConnection. swift-export exposes that enum as a
// public mangled type; the typealias on DeviceConnection itself is internal.
public typealias DeviceConnectionType = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_discovery_DeviceConnection_DeviceConnectionType

// Share
public typealias ShareSheetDismissTrigger = ExportedKotlinPackages.com.carlom.klardrop.common.share.ShareSheetDismissTrigger
public typealias ShareSheetDismissPolicy = ExportedKotlinPackages.com.carlom.klardrop.common.share.ShareSheetDismissPolicy

// Utils
public typealias DeviceType = ExportedKotlinPackages.com.carlom.klardrop.common.utils.DeviceType

// Crash reporting
public typealias CrashReporter = ExportedKotlinPackages.com.klardrop.common.CrashReporter
public typealias ReportOutcome = ExportedKotlinPackages.com.klardrop.common.ReportOutcome

// macOS control plane
#if os(macOS)
public typealias ControlPlane = ExportedKotlinPackages.com.carlom.klardrop.control.ControlPlane
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
    public typealias Unreachable = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_communication_Reachability_Unreachable
}

extension UpdateStatus {
    public typealias Available = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Available
    public typealias Failed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Failed
    public typealias Unknown = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_Unknown
    public typealias UpToDate = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_update_UpdateStatus_UpToDate
}

extension ReceiveMessageStatus {
    public typealias PendingAuthorization = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_PendingAuthorization
    public typealias Completed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Completed
    public typealias Failed = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Failed
    public typealias Progress = KlardropCommon._ExportedKotlinPackages_com_carlom_klardrop_common_receiver_ReceiveMessageStatus_Progress
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

extension ShareSheetDismissTrigger {
    public static let selectDevice = ShareSheetDismissTrigger.SelectDevice
    public static let sendTap = ShareSheetDismissTrigger.SendTap
    public static let swipeAway = ShareSheetDismissTrigger.SwipeAway
    public static let completed = ShareSheetDismissTrigger.Completed
    public static let userHide = ShareSheetDismissTrigger.UserHide
    public static let emptyPayload = ShareSheetDismissTrigger.EmptyPayload
}

