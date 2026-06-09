package com.carlom.klardrop.common

import com.carlom.klardrop.common.utils.log
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path

interface FileManager {
  fun prepareSaveFile(fileName: String, mimeType: String): FileTransfer
  fun getReadStreamFrom(file: PlatformFile): RawSource
  suspend fun openFile(filePath: String): Boolean // New method
  suspend fun openUrl(url: String): Boolean
}

interface FileTransfer {
  val bufferedSink: Sink
  suspend fun onTransferCompleted(): Path?
  suspend fun onTransferFailed()
}

/**
 * Sanitizes an attacker-controlled file name so it is safe to use as a bare filename inside a
 * storage directory.
 *
 * Rules (applied in order):
 * 1. Replace Windows-style backslashes with forward slashes so they are treated as separators.
 * 2. Keep only the last path component (strip any directory traversal prefix like "../../").
 * 3. Reject dot-only names ("", ".", "..") — fall back to the default.
 * 4. The result is guaranteed to contain neither '/' nor '\', so it can never escape the parent
 *    directory when joined with [Path].
 */
internal fun sanitizeFileName(raw: String, default: String = "file"): String {
  // Normalise Windows separators so they count as path separators.
  val normalized = raw.replace('\\', '/')
  // Take the last segment after any directory separator.
  val bare = normalized.substringAfterLast('/')
  // Reject empty / dot-only / double-dot names.
  return if (bare.isEmpty() || bare == "." || bare == "..") default else bare
}

fun getAvailableFilePath(parentPath: Path, requestedFileName: String, fileSystem: FileSystem): Path {
  val safeFileName = sanitizeFileName(requestedFileName)
  val resolvedParent = fileSystem.resolve(parentPath)
  val firstChoice = Path(resolvedParent, safeFileName)

  // Containment guard: verify the constructed path is strictly under resolvedParent using
  // string comparison. We do NOT call fileSystem.resolve(firstChoice) here because on the
  // JVM back-end that throws FileNotFoundException for non-existent paths. Instead we rely
  // on the invariant established by sanitizeFileName: safeFileName contains no '/' or '\'
  // and is not "." or "..", so Path(resolvedParent, safeFileName) is always one level deep.
  // The check below is defence-in-depth for unexpected platform behaviours.
  val resolvedParentStr = resolvedParent.toString()
  val firstChoiceStr = firstChoice.toString()
  if (!firstChoiceStr.startsWith("$resolvedParentStr/") &&
      !firstChoiceStr.startsWith("$resolvedParentStr\\")) {
    throw SecurityException(
      "Sanitised file name '$safeFileName' still escapes parent '$resolvedParent'. " +
        "Original name was '${requestedFileName}'."
    )
  }

  if (!fileSystem.exists(firstChoice)) {
    return firstChoice
  }

  // Split "dog.jpeg" into base "dog" and extension ".jpeg" so the counter goes before the
  // extension: dog-1.jpeg, dog-2.jpeg, … rather than dog.jpeg-1.
  val extension = safeFileName.substringAfterLast(".", "").let {
    if (it.isEmpty()) "" else ".$it"
  }
  val baseName = safeFileName.substring(0, safeFileName.length - extension.length)

  var counter = 1
  var destinationPath = Path(resolvedParent, "$baseName-$counter$extension")
  while (fileSystem.exists(destinationPath)) {
    counter++
    destinationPath = Path(resolvedParent, "$baseName-$counter$extension")
  }

  log("FileManagerImpl", "File '$requestedFileName' already exists, saving as: ${destinationPath.name}")
  return destinationPath
}
