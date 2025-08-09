package com.carlom.klardrop.common.features

import kotlinx.coroutines.flow.Flow

/**
 * Platform-specific clipboard monitoring interface that provides efficient
 * clipboard change detection without constant polling
 */
interface ClipboardMonitor {
    /**
     * Observe clipboard changes as a Flow of clipboard content.
     * Implementations should use platform-specific event listeners where available
     * and fall back to optimized polling where necessary.
     * 
     * @return Flow of clipboard content strings when clipboard changes
     */
    fun observeChanges(): Flow<String>
    
    /**
     * Stop monitoring clipboard changes and clean up resources
     */
    fun stopMonitoring()
}

/**
 * Expected platform-specific implementation factory
 */
expect fun createClipboardMonitor(readerWriter: ClipboardReaderWriter): ClipboardMonitor