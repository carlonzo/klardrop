package com.carlom.klardrop.common.trust.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.receiver.MessageReceiver
import com.carlom.klardrop.common.trust.TrustManager
import com.carlom.klardrop.common.trust.clipboard.TrustClipboardSyncManager
import com.carlom.klardrop.common.database.AppDatabase
import com.carlom.klardrop.common.trust.discovery.TrustAwareDiscoveryUtils
import com.carlom.klardrop.common.trust.receiver.withTrustAwareness
import com.carlom.klardrop.common.trust.storage.SecureKeyStorageFactory
import com.carlom.klardrop.common.utils.Coroutines
import com.carlom.klardrop.common.utils.DeviceType
import com.carlom.klardrop.common.utils.OsType
import com.carlom.klardrop.protos.trust.TrustMessage
import kotlinx.coroutines.CoroutineScope

/**
 * Dependency injection module for trust-related components
 */
class TrustModule(
    private val applicationInfo: ApplicationInfo,
    private val coroutines: Coroutines,
    private val appDatabase: AppDatabase,
    private val internalPlatformDependencies: InternalPlatformDependencies,
    private val currentDeviceProvider: CurrentDeviceProvider,
    private val clipboardManager: ClipboardManager,
    private val sendTrustMessage: suspend (deviceId: String, message: TrustMessage) -> Unit
) {
    
    private val trustScope: CoroutineScope by lazy {
        coroutines.newScope(coroutines.ioDispatcher)
    }
    
    private val secureKeyStorageFactory: SecureKeyStorageFactory by lazy {
        internalPlatformDependencies.secureKeyStorageFactory()
    }
    
    val trustManager: TrustManager by lazy {
        // Get device info synchronously - this might need to be cached or initialized elsewhere
        val deviceName = CommonPlatformDependencies.getDeviceName()
        val deviceType = CommonPlatformDependencies.deviceType()
        
        TrustManager(
            database = appDatabase,
            secureKeyStorageFactory = secureKeyStorageFactory,
            deviceName = deviceName,
            deviceType = mapDeviceType(deviceType),
            scope = trustScope,
            sendTrustMessage = sendTrustMessage
        )
    }
    
    val trustAwareDiscoveryUtils: TrustAwareDiscoveryUtils by lazy {
        TrustAwareDiscoveryUtils(
            baseUtils = KlardropDiscoveryUtils(),
            trustManager = trustManager
        )
    }
    
    val trustClipboardSyncManager: TrustClipboardSyncManager by lazy {
        TrustClipboardSyncManager(
            clipboardManager = clipboardManager,
            trustManager = trustManager,
            scope = trustScope
        )
    }
    
    /**
     * Wrap a MessageReceiver with trust awareness
     */
    fun wrapMessageReceiver(baseReceiver: MessageReceiver): MessageReceiver {
        return baseReceiver.withTrustAwareness(trustManager, trustScope)
    }
    
    private fun mapDeviceType(deviceType: com.carlom.klardrop.common.utils.DeviceType): com.carlom.klardrop.protos.trust.DeviceType {
        return when (deviceType) {
            com.carlom.klardrop.common.utils.DeviceType.PHONE -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_ANDROID
            com.carlom.klardrop.common.utils.DeviceType.TABLET -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_ANDROID
            com.carlom.klardrop.common.utils.DeviceType.LAPTOP -> when (CommonPlatformDependencies.osType()) {
                OsType.WINDOWS -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_WINDOWS
                OsType.LINUX -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_LINUX
                OsType.MAC -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_MACOS
                else -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_UNKNOWN
            }
            else -> com.carlom.klardrop.protos.trust.DeviceType.DEVICE_TYPE_UNKNOWN
        }
    }
}