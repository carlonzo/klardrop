package com.carlom.klardrop.common.features

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Apple platform clipboard monitor using NSPasteboard change count for
 * efficient monitoring. Currently uses optimized polling until native
 * NSPasteboard integration is implemented.
 */
class AppleClipboardMonitor(
    private val readerWriter: ClipboardReaderWriter
) : ClipboardMonitor {
    
    companion object {
        private const val POLLING_INTERVAL_MS = 1000L // Optimized polling interval
    }
    
    override fun observeChanges(): Flow<String> = flow {
        var lastContent = ""
        
        // TODO: Implement NSPasteboard.changeCount monitoring for more efficient detection
        // For now, use optimized polling (1 second instead of 500ms)
        while (currentCoroutineContext().isActive) {
            try {
                val content = readerWriter.read()
                if (content != lastContent && content.isNotEmpty()) {
                    emit(content)
                    lastContent = content
                }
            } catch (e: Exception) {
                // Ignore clipboard read errors and continue
            }
            delay(POLLING_INTERVAL_MS)
        }
    }.distinctUntilChanged()
    
    override fun stopMonitoring() {
        // Cleanup is handled by the Flow's coroutine cancellation
    }
}

/**
 * Apple platforms implementation of the factory function
 */
actual fun createClipboardMonitor(readerWriter: ClipboardReaderWriter): ClipboardMonitor {
    return AppleClipboardMonitor(readerWriter)
}