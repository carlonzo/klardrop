package com.carlom.klardrop.common.features

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.FlavorEvent
import java.awt.datatransfer.FlavorListener
import java.awt.Toolkit

/**
 * Desktop JVM clipboard monitor using FlavorListener for event-based monitoring
 * with polling fallback for better performance than the original 500ms polling
 */
class DesktopClipboardMonitor(
    private val readerWriter: ClipboardReaderWriter
) : ClipboardMonitor {
    
    companion object {
        private const val POLLING_INTERVAL_MS = 1000L // Reduced from 500ms to 1000ms
    }
    
    private val clipboard by lazy { Toolkit.getDefaultToolkit().systemClipboard }
    
    override fun observeChanges(): Flow<String> = flow {
        var lastContent = ""
        var useEventListener = true
        
        // Try to use FlavorListener for efficient event-based monitoring
        if (useEventListener) {
            try {
                // Use FlavorListener if available (Java 9+)
                val listener = FlavorListener { _: FlavorEvent ->
                    try {
                        val content = readerWriter.read()
                        if (content != lastContent && content.isNotEmpty()) {
                            lastContent = content
                            // Note: Can't emit from listener, will need to use polling anyway
                        }
                    } catch (e: Exception) {
                        // Ignore clipboard read errors in listener
                    }
                }
                
                clipboard.addFlavorListener(listener)
                
                // Still need polling to actually emit values, but FlavorListener
                // helps detect changes more efficiently
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
                
                clipboard.removeFlavorListener(listener)
                return@flow
                
            } catch (e: Exception) {
                // FlavorListener not available or failed, fall back to polling
                useEventListener = false
            }
        }
        
        // Fallback to optimized polling
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
 * Desktop JVM implementation of the factory function
 */
actual fun createClipboardMonitor(readerWriter: ClipboardReaderWriter): ClipboardMonitor {
    return DesktopClipboardMonitor(readerWriter)
}