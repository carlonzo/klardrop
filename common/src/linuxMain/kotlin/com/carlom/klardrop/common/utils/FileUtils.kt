@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.carlom.klardrop.common.utils

import kotlinx.cinterop.*
import platform.posix.*

/**
 * Reads the text content of a file, or returns null if not accessible.
 */
internal fun readFileText(path: String): String? = memScoped {
  val fd = open(path, O_RDONLY)
  if (fd < 0) return null
  try {
    val stat = alloc<stat>()
    if (fstat(fd, stat.ptr) != 0) return null
    val size = stat.st_size.toInt()
    if (size == 0) return ""
    val buffer = ByteArray(size)
    var readBytes = 0
    buffer.usePinned { pinned ->
      while (readBytes < size) {
        val n = read(fd, pinned.addressOf(readBytes), (size - readBytes).toULong())
        if (n <= 0) break
        readBytes += n.toInt()
      }
    }
    if (readBytes == size) buffer.decodeToString() else null
  } finally {
    close(fd)
  }
}

/**
 * Reads the raw byte content of a file, or returns null if not accessible.
 */
internal fun readFileBytes(path: String): ByteArray? = memScoped {
  val fd = open(path, O_RDONLY)
  if (fd < 0) return null
  try {
    val stat = alloc<stat>()
    if (fstat(fd, stat.ptr) != 0) return null
    val size = stat.st_size.toInt()
    if (size == 0) return ByteArray(0)
    val buffer = ByteArray(size)
    var readBytes = 0
    buffer.usePinned { pinned ->
      while (readBytes < size) {
        val n = read(fd, pinned.addressOf(readBytes), (size - readBytes).toULong())
        if (n <= 0) break
        readBytes += n.toInt()
      }
    }
    if (readBytes == size) buffer else null
  } finally {
    close(fd)
  }
}

/**
 * Writes bytes atomically with file permissions 0600 (owner read/write only).
 */
internal fun writeFile0600(path: String, bytes: ByteArray) = memScoped {
  val tmpPath = "$path.tmp"
  val fd = open(tmpPath, O_WRONLY or O_CREAT or O_TRUNC, (S_IRUSR or S_IWUSR).toUInt())
  if (fd < 0) throw IllegalStateException("Failed to open file for writing at $tmpPath")
  try {
    var written = 0
    bytes.usePinned { pinned ->
      while (written < bytes.size) {
        val n = write(fd, pinned.addressOf(written), (bytes.size - written).toULong())
        if (n <= 0) break
        written += n.toInt()
      }
    }
    fchmod(fd, (S_IRUSR or S_IWUSR).toUInt())
  } finally {
    close(fd)
  }
  if (rename(tmpPath, path) != 0) {
    unlink(tmpPath)
    throw IllegalStateException("Failed to rename $tmpPath to $path")
  }
}

/**
 * Writes string atomically with file permissions 0600 (owner read/write only).
 */
internal fun writeFile0600(path: String, content: String) {
  writeFile0600(path, content.encodeToByteArray())
}

/**
 * Ensures directory hierarchy exists with permissions 0700 (owner read/write/exec only).
 */
internal fun ensureDirectory0700(dir: String) {
  val parts = dir.split('/').filter { it.isNotEmpty() }
  var current = if (dir.startsWith('/')) "/" else ""
  for (part in parts) {
    current = if (current == "/") "/$part" else "$current/$part"
    mkdir(current, (S_IRWXU).toUInt())
  }
  chmod(dir, (S_IRWXU).toUInt())
}

/**
 * Ensures the directory hierarchy exists, creating only what is missing.
 *
 * Deliberately different from [ensureDirectory0700]: `mkdir` on an existing
 * directory is a no-op that leaves its mode alone, so this never narrows a
 * directory it did not create. That matters for the download directory, which is
 * normally a user-owned folder such as ~/Downloads whose permissions belong to
 * the user, not to Klardrop.
 */
internal fun ensureDirectory(dir: String) {
  val parts = dir.split('/').filter { it.isNotEmpty() }
  var current = if (dir.startsWith('/')) "/" else ""
  for (part in parts) {
    current = if (current == "/") "/$part" else "$current/$part"
    // 0777 masked by the process umask, which is the mode every other tool
    // creating a directory would get.
    mkdir(current, (S_IRWXU or S_IRWXG or S_IRWXO).toUInt())
  }
}
