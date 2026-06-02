package com.carlom.klardrop

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.files.Path

/**
 * Swift-facing helper: build a FileKit PlatformFile from an absolute filesystem path
 * (e.g. a UIDocumentPicker / share-extension URL.path). Mirrors the existing
 * PlatformFile(Path(sourcePath)) usage in DeviceChatViewModel.retryFileTransfer.
 */
fun platformFileFromPath(path: String): PlatformFile = PlatformFile(Path(path))
