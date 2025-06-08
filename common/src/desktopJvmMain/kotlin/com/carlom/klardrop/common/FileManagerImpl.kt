package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.Coroutines
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.withContext
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.awt.Desktop
import java.io.File // java.io.File
import java.nio.file.Files // java.nio.file.Files for creating temp dir more robustly


actual class FileManagerImpl(
    private val coroutines: Coroutines, // Assuming Coroutines provides dispatchers
    private val platformFileSystem: PlatformFileSystem // From common/utils, might be useful for paths
) : FileManager {

    // Define a directory for saving files, e.g., in user's temp or documents
    private val saveDir: File = File(System.getProperty("java.io.tmpdir"), "klardrop_files").apply {
        if (!exists()) mkdirs()
    }

    override fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer {
        // Ensure unique file name or use getAvailableFilePath logic if ported from commonMain
        val file = File(saveDir, fileName)

        return object : FileTransfer {
            override val bufferedSink: Sink = file.outputStream().asSink().buffered()
            // private var finalPath: String? = null // Not strictly needed if file.absolutePath is used directly

            override suspend fun onTransferCompleted() {
                // finalPath = file.absolutePath
                println("File saved to: ${file.absolutePath}")
            }

            override suspend fun onTransferFailed() {
                if (file.exists()) {
                    file.delete()
                }
                println("File transfer failed, deleted partial file: ${file.name}")
            }
        }
    }

    override fun getReadStreamFrom(file: PlatformFile): RawSource {
        // Assuming file.path is a valid string path on desktop
        val javaFile = File(file.path ?: throw IllegalArgumentException("File path is null for PlatformFile"))
        if (!javaFile.exists()) throw java.io.FileNotFoundException("File not found: ${file.path}")
        return javaFile.inputStream().asSource().buffered()
    }

    override suspend fun openFile(filePath: String): Boolean {
        return withContext(coroutines.ioDispatcher) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                        Desktop.getDesktop().open(file)
                        true
                    } else {
                        println("OpenFile: Desktop.Action.OPEN not supported for $filePath")
                        false
                    }
                } else {
                    println("OpenFile: File does not exist at $filePath")
                    false
                }
            } catch (e: Exception) {
                println("OpenFile: Error opening file $filePath - ${e.localizedMessage}")
                e.printStackTrace()
                false
            }
        }
    }
}
