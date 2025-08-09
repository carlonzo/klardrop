package com.carlom.klardrop.common.features

import android.content.ClipboardManager as AndroidClipboardManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Android-specific clipboard monitor using OnPrimaryClipChangedListener
 * for efficient event-based clipboard monitoring instead of polling
 */
class AndroidClipboardMonitor(
    private val context: Context,
    private val readerWriter: ClipboardReaderWriter
) : ClipboardMonitor {
    
    private val androidClipboardManager by lazy { 
        context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager
    }
    
    override fun observeChanges(): Flow<String> = callbackFlow {
        val listener = AndroidClipboardManager.OnPrimaryClipChangedListener {
            val content = readerWriter.read()
            if (content.isNotEmpty()) {
                trySend(content)
            }
        }
        
        // Add the listener
        androidClipboardManager.addPrimaryClipChangedListener(listener)
        
        // Send initial value
        val initialContent = readerWriter.read()
        if (initialContent.isNotEmpty()) {
            trySend(initialContent)
        }
        
        awaitClose {
            androidClipboardManager.removePrimaryClipChangedListener(listener)
        }
    }.distinctUntilChanged()
    
    override fun stopMonitoring() {
        // Cleanup is handled by the Flow's awaitClose
    }
}

/**
 * Android implementation of the factory function
 * Gets context from the ClipboardReaderWriter instance
 */
actual fun createClipboardMonitor(readerWriter: ClipboardReaderWriter): ClipboardMonitor {
    // Extract context from the Android ClipboardReaderWriter
    val contextField = readerWriter::class.java.getDeclaredField("context")
    contextField.isAccessible = true
    val context = contextField.get(readerWriter) as Context
    return AndroidClipboardMonitor(context, readerWriter)
}