package com.carlom.klardrop.common.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxPathsTest {

  @Test
  fun testDefaultPathsResolution() {
    val env = mapOf("HOME" to "/home/alice")
    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { null },
      defaultHome = { "/home/alice" }
    )

    assertEquals("/home/alice", paths.home)
    assertEquals("/home/alice/.klardrop", paths.trustDir)
    assertEquals("/home/alice/.local/share/klardrop", paths.dataDir)
    assertEquals("/home/alice/.local/share/klardrop/databases", paths.databasesDir)
    assertEquals("/home/alice/.config/klardrop", paths.configDir)
    assertEquals("/home/alice/.cache/klardrop", paths.cacheDir)
    assertEquals("/home/alice/Downloads", paths.downloadDir)
  }

  @Test
  fun testKlardropHomeOverride() {
    val env = mapOf(
      "HOME" to "/home/bob",
      "KLARDROP_HOME" to "/opt/isolated-klardrop",
    )
    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { null },
      defaultHome = { "/home/bob" }
    )

    assertEquals("/home/bob", paths.home)
    assertEquals("/opt/isolated-klardrop/trust", paths.trustDir)
    assertEquals("/opt/isolated-klardrop", paths.dataDir)
    assertEquals("/opt/isolated-klardrop/databases", paths.databasesDir)
    assertEquals("/opt/isolated-klardrop/config", paths.configDir)
    assertEquals("/opt/isolated-klardrop/cache", paths.cacheDir)
    assertEquals("/home/bob/Downloads", paths.downloadDir)
  }

  @Test
  fun testXdgEnvOverrides() {
    val env = mapOf(
      "HOME" to "/home/charlie",
      "XDG_DATA_HOME" to "/mnt/fast/data",
      "XDG_CONFIG_HOME" to "/mnt/fast/config",
      "XDG_CACHE_HOME" to "/tmp/cache",
    )
    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { null },
      defaultHome = { "/home/charlie" }
    )

    assertEquals("/home/charlie", paths.home)
    assertEquals("/home/charlie/.klardrop", paths.trustDir)
    assertEquals("/mnt/fast/data/klardrop", paths.dataDir)
    assertEquals("/mnt/fast/data/klardrop/databases", paths.databasesDir)
    assertEquals("/mnt/fast/config/klardrop", paths.configDir)
    assertEquals("/tmp/cache/klardrop", paths.cacheDir)
  }

  @Test
  fun testXdgUserDirsParsing() {
    val env = mapOf(
      "HOME" to "/home/dave",
      "XDG_CONFIG_HOME" to "/home/dave/.config",
    )

    val userDirsContent = """
      # XDG user dirs config
      XDG_DESKTOP_DIR="${'$'}HOME/Desktop"
      XDG_DOWNLOAD_DIR="${'$'}HOME/Incoming"
      XDG_DOCUMENTS_DIR="${'$'}{HOME}/Docs"
    """.trimIndent()

    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { path ->
        if (path == "/home/dave/.config/user-dirs.dirs") userDirsContent else null
      },
      defaultHome = { "/home/dave" }
    )

    assertEquals("/home/dave/Incoming", paths.downloadDir)
  }

  @Test
  fun testXdgUserDirsAbsolutePath() {
    val env = mapOf("HOME" to "/home/eve")
    val userDirsContent = """
      XDG_DOWNLOAD_DIR="/shared/downloads"
    """.trimIndent()

    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { userDirsContent },
      defaultHome = { "/home/eve" }
    )

    assertEquals("/shared/downloads", paths.downloadDir)
  }

  @Test
  fun testXdgUserDirsFallbackWhenMissing() {
    val env = mapOf("HOME" to "/home/frank")
    val paths = LinuxPaths.resolve(
      env = { env[it] },
      readFile = { null },
      defaultHome = { "/home/frank" }
    )

    assertEquals("/home/frank/Downloads", paths.downloadDir)
  }
}
