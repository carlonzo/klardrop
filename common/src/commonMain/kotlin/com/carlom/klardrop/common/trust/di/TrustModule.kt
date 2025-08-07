package com.carlom.klardrop.common.trust.di

import com.carlom.klardrop.common.ApplicationInfo
import com.carlom.klardrop.common.CommonPlatformDependencies
import com.carlom.klardrop.common.InternalPlatformDependencies
import com.carlom.klardrop.common.discovery.CurrentDeviceProvider
import com.carlom.klardrop.common.discovery.KlardropDiscoveryUtils
import com.carlom.klardrop.common.features.ClipboardManager
import com.carlom.klardrop.common.features.ClipboardMonitor
import com.carlom.klardrop.common.features.createClipboardMonitor
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
    private val sendTrustMessage: suspend (deviceId: String, message: Any) -> Unit
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
            deviceType = deviceType,
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
    
    private val clipboardMonitor: ClipboardMonitor by lazy {
        createClipboardMonitor(internalPlatformDependencies.clipboardReaderWriter())
    }
    
    val trustClipboardSyncManager: TrustClipboardSyncManager by lazy {
        TrustClipboardSyncManager(
            clipboardManager = clipboardManager,
            clipboardMonitor = clipboardMonitor,
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
    
    // mapDeviceType function removed - no longer needed since we're using the same DeviceType enum
}