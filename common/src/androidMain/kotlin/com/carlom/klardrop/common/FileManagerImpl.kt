package com.carlom.klardrop.common

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.carlom.klardrop.common.utils.Coroutines
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.files.Path // This is kotlinx.io.files.Path, ensure it's the correct one
import kotlinx.io.files.SystemFileSystem
import kotlinx.coroutines.withContext
import java.io.File // This is java.io.File

actual class FileManagerImpl(
    private val context: Context,
    private val coroutines: Coroutines, // Assuming Coroutines provides dispatchers
    private val platformFileSystem: PlatformFileSystem // From common/utils, might be useful for paths
) : FileManager {

    // Define where files are saved, to align with FileProvider paths
    private val sharedDir = File(context.cacheDir, "shared")

    init {
        if (!sharedDir.exists()) {
            sharedDir.mkdirs()
        }
    }

    override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
        val file = File(sharedDir, fileName) // Using a unique name might be better
        // Potentially use getAvailableFilePath from commonMain if Path utilities align
        // For now, simple save.

        return object : FileTransfer {
            // Ensure this sink is properly managed (closed) by the caller or stream handler
            override val bufferedSink: Sink = file.outputStream().asSink().buffered()
            private var finalPath: String? = null

            override suspend fun onTransferCompleted() {
                finalPath = file.absolutePath
                // File is saved, path is file.absolutePath
                // This path needs to be the one used for FileProvider
                println("File saved to: ${file.absolutePath}")
            }

            override suspend fun onTransferFailed() {
                // Delete partially written file
                if (file.exists()) {
                    file.delete()
                }
                println("File transfer failed, deleted partial file: ${file.name}")
            }
            // Consider adding a method to get finalPath if needed by caller
        }
    }

    // This implementation assumes PlatformFile is a java.io.File or can provide one.
    // This might need adjustment based on actual PlatformFile structure on Android.
    override fun getReadStreamFrom(file: PlatformFile): RawSource {
        // This is a placeholder. The actual implementation depends on what PlatformFile is.
        // If file.path gives a usable string path:
        val javaFile = File(file.path ?: throw IllegalArgumentException("File path is null"))
        if (!javaFile.exists()) throw java.io.FileNotFoundException("File not found: ${file.path}")
        return javaFile.inputStream().asSource().buffered()
    }


    override suspend fun openFile(filePath: String): Boolean {
        return withContext(coroutines.ioDispatcher) {
            try {
                val file = File(filePath)
                if (!file.exists()) {
                    println("OpenFile: File does not exist at $filePath")
                    return@withContext false
                }

                val authority = context.applicationContext.packageName + ".provider"
                val uri = FileProvider.getUriForFile(context, authority, file)

                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Important if calling from non-Activity context
                }

                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    println("OpenFile: No activity found to handle Intent for URI: $uri")
                    false
                }
            } catch (e: Exception) {
                println("OpenFile: Error opening file $filePath - ${e.localizedMessage}")
                e.printStackTrace() // Log detailed error
                false
            }
        }
    }
}
