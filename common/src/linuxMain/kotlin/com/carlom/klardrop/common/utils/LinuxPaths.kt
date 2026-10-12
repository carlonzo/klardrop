package com.carlom.klardrop.common.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.geteuid
import platform.posix.getpwuid

data class ResolvedLinuxPaths(
  val home: String,
  val trustDir: String,
  val dataDir: String,
  val databasesDir: String,
  val configDir: String,
  val cacheDir: String,
  val downloadDir: String,
)

object LinuxPaths {

  @OptIn(ExperimentalForeignApi::class)
  fun defaultHome(): String {
    return getenv("HOME")?.toKString()?.ifBlank { null }
      ?: getpwuid(geteuid())?.pointed?.pw_dir?.toKString()?.ifBlank { null }
      ?: "/tmp"
  }

  fun resolve(
    env: (String) -> String? = {
      @OptIn(ExperimentalForeignApi::class)
      getenv(it)?.toKString()?.ifBlank { null }
    },
    readFile: (String) -> String? = ::readFileText,
    defaultHome: () -> String = ::defaultHome,
  ): ResolvedLinuxPaths {
    val home = env("HOME")?.ifBlank { null } ?: defaultHome()
    val klardropHome = env("KLARDROP_HOME")?.ifBlank { null }

    val trustDir: String
    val dataDir: String
    val databasesDir: String
    val configDir: String
    val cacheDir: String

    if (klardropHome != null) {
      trustDir = "$klardropHome/trust"
      dataDir = klardropHome
      databasesDir = "$klardropHome/databases"
      configDir = "$klardropHome/config"
      cacheDir = "$klardropHome/cache"
    } else {
      trustDir = "$home/.klardrop"
      val dataHome = env("XDG_DATA_HOME")?.ifBlank { null } ?: "$home/.local/share"
      dataDir = "$dataHome/klardrop"
      databasesDir = "$dataDir/databases"
      val configHome = env("XDG_CONFIG_HOME")?.ifBlank { null } ?: "$home/.config"
      configDir = "$configHome/klardrop"
      val cacheHome = env("XDG_CACHE_HOME")?.ifBlank { null } ?: "$home/.cache"
      cacheDir = "$cacheHome/klardrop"
    }

    val configHomeForUserDirs = if (klardropHome != null) {
      "$klardropHome/config"
    } else {
      env("XDG_CONFIG_HOME")?.ifBlank { null } ?: "$home/.config"
    }
    val userDirsFile = "$configHomeForUserDirs/user-dirs.dirs"
    val userDirsContent = readFile(userDirsFile)
    val parsedDownloadDir = if (userDirsContent != null) {
      parseXdgUserDir(userDirsContent, "XDG_DOWNLOAD_DIR", home)
    } else {
      null
    }
    val downloadDir = parsedDownloadDir ?: "$home/Downloads"

    return ResolvedLinuxPaths(
      home = home,
      trustDir = trustDir,
      dataDir = dataDir,
      databasesDir = databasesDir,
      configDir = configDir,
      cacheDir = cacheDir,
      downloadDir = downloadDir,
    )
  }

  fun parseXdgUserDir(content: String, targetKey: String, home: String): String? {
    for (rawLine in content.lines()) {
      val line = rawLine.trim()
      if (line.startsWith("#") || line.isBlank()) continue
      val equalsIdx = line.indexOf('=')
      if (equalsIdx <= 0) continue
      val key = line.substring(0, equalsIdx).trim()
      if (key != targetKey) continue
      var value = line.substring(equalsIdx + 1).trim()
      if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
        value = value.substring(1, value.length - 1)
      }
      value = value.replace("\\\"", "\"").replace("\\\\", "\\")
      value = value.replace("\$HOME", home).replace("\${HOME}", home)
      if (value.isNotBlank()) return value
    }
    return null
  }

  val home: String get() = resolve().home
  val trustDir: String get() = resolve().trustDir
  val dataDir: String get() = resolve().dataDir
  val databasesDir: String get() = resolve().databasesDir
  val configDir: String get() = resolve().configDir
  val cacheDir: String get() = resolve().cacheDir
  val downloadDir: String get() = resolve().downloadDir
}
