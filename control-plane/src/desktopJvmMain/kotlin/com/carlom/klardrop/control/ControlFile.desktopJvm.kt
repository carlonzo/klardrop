package com.carlom.klardrop.control

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

internal actual fun writeControlFile(port: Int, token: String, capabilities: List<String>) {
  val pathStr = resolveControlFilePath() ?: return
  val file = File(pathStr)
  file.parentFile?.let { dir ->
    val dirPath = dir.toPath()
    if (supportsPosix()) {
      // Restricted at creation rather than afterwards: creating first and narrowing the
      // permissions second would leave a window in which the token file is readable by
      // everyone the umask allows.
      Files.createDirectories(dirPath, posixAttribute(DIRECTORY_PERMISSIONS))
    } else {
      if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
        throw IllegalStateException("cannot create control directory ${dir.absolutePath}")
      }
    }
    protect(dirPath, isDirectory = true)
  }

  val content = controlFileJson(port, token, capabilities)
  val path = file.toPath()
  if (Files.exists(path)) {
    Files.delete(path)
  }
  if (supportsPosix()) {
    Files.createFile(path, posixAttribute(FILE_PERMISSIONS))
  } else {
    // Windows cannot pass an ACL at creation through java.nio; the profile's default
    // ACLs are already user-private, and protect() narrows them to the owner alone.
    Files.createFile(path)
  }
  protect(path, isDirectory = false)
  Files.writeString(path, content)
}

private const val DIRECTORY_PERMISSIONS = "rwx------"
private const val FILE_PERMISSIONS = "rw-------"

private fun posixAttribute(permissions: String): java.nio.file.attribute.FileAttribute<*> =
  PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))

/**
 * Restricts [path] to its owner, so the bearer token inside is never readable by another user.
 *
 * Uses the `posix` view where the filesystem supports it (Unix), otherwise an owner-only ACL
 * (Windows). A filesystem with neither view throws instead of writing the file unprotected —
 * a world-readable token is worse than a failed start.
 */
private fun protect(path: Path, isDirectory: Boolean) {
  if (supportsPosix()) {
    val perms = PosixFilePermissions.fromString(
      if (isDirectory) DIRECTORY_PERMISSIONS else FILE_PERMISSIONS,
    )
    Files.setPosixFilePermissions(path, perms)
    return
  }

  val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
    ?: throw IllegalStateException("cannot restrict permissions on $path: no posix or acl support")
  val owner: UserPrincipal = runCatching {
    Files.getOwner(path)
  }.getOrElse {
    throw IllegalStateException("cannot resolve the owner of $path", it)
  }
  // Set the ACL explicitly rather than letting it be inherited: an inherited entry could grant
  // another principal access to a file holding the control token.
  val ownerEntry: AclEntry = AclEntry.newBuilder()
    .setType(AclEntryType.ALLOW)
    .setPrincipal(owner)
    .setPermissions(AclEntryPermission.values().toSet())
    .build()
  view.acl = listOf(ownerEntry)
}

private fun supportsPosix(): Boolean =
  FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

internal actual fun deleteControlFile(expectedToken: String?) {
  val pathStr = resolveControlFilePath() ?: return
  val file = File(pathStr)
  if (!file.exists()) return

  if (expectedToken != null) {
    val currentToken = runCatching {
      val text = file.readText()
      Regex(""""token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
    }.getOrNull()
    if (currentToken != expectedToken) {
      return
    }
  }

  runCatching { file.delete() }
}

/**
 * `$XDG_RUNTIME_DIR/klardrop/control.json`, falling back to `$HOME/.cache` — the same two
 * locations every Klardrop client (Kotlin, native, Qt) resolves.
 */
actual fun resolveControlFilePath(): String? {
  if (isWindows()) {
    val localAppData = System.getenv("LOCALAPPDATA")
      ?: return null
    return File(File(localAppData, "Klardrop"), "control.json").absolutePath
  }
  val xdg = System.getenv("XDG_RUNTIME_DIR")
  val dir = if (!xdg.isNullOrEmpty()) {
    File(xdg, "klardrop")
  } else {
    val home = System.getProperty("user.home", ".")
    File(File(home, ".cache"), "klardrop")
  }
  return File(dir, "control.json").absolutePath
}

private fun isWindows(): Boolean =
  System.getProperty("os.name").lowercase().contains("win")

/**
 * The JVM host always has a location: XDG runtime dir, `%LOCALAPPDATA%` on Windows, `$HOME`
 * otherwise. The one null case is Windows with no `LOCALAPPDATA`, which now stops
 * [ControlPlane.start] instead of quietly serving an unauthenticated loopback listener.
 */
internal actual val controlFileOptional: Boolean = false

/** Every production route works on the JVM host; nothing is withheld. */
internal actual val platformUnavailableCapabilities: Set<String> = emptySet()