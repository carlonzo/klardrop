package com.carlom.klardrop.common.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Linux system-JRE wrapper execs `java`, so ProcessHandle.command() is
 * `/usr/bin/java`. Channel detection and tarball self-update must keep using
 * the *script* path (`…/klardrop/bin/klardrop`) from `-Dklardrop.launcher`.
 *
 * These tests fail if currentLauncherPath() goes back to ProcessHandle-only:
 * the process command of this test JVM is a java binary, not a klardrop script.
 */
class LauncherPathTest {

  @Test
  fun propertyBeatsProcessCommandWhichIsTheJavaBinary() {
    val script = "/home/x/.local/lib/klardrop/bin/klardrop"
    val path = resolveLauncherPath(
      property = script,
      env = null,
      processCommand = "/usr/bin/java",
    )
    assertEquals(script, path)
    assertEquals(
      Path.of("/home/x/.local/lib/klardrop"),
      linuxInstallRoot(path!!, home = "/home/x"),
    )
  }

  @Test
  fun envBeatsProcessCommandWhenPropertyIsUnset() {
    val script = "/opt/klardrop/bin/klardrop"
    val path = resolveLauncherPath(
      property = null,
      env = script,
      processCommand = "/usr/bin/java",
    )
    assertEquals(script, path)
    assertEquals(Path.of("/opt/klardrop"), linuxInstallRoot(path!!, home = "/home/x"))
  }

  @Test
  fun propertyBeatsEnv() {
    val path = resolveLauncherPath(
      property = "/opt/klardrop/bin/klardrop",
      env = "/usr/bin/java",
      processCommand = "/usr/bin/java",
    )
    assertEquals("/opt/klardrop/bin/klardrop", path)
  }

  @Test
  fun blankPropertyFallsThroughToEnvThenProcessCommand() {
    assertEquals(
      "/opt/klardrop/bin/klardrop",
      resolveLauncherPath(property = "  ", env = "/opt/klardrop/bin/klardrop", processCommand = "/usr/bin/java"),
    )
    // A path that does not exist: toRealPath would otherwise follow /usr/bin/java.
    assertEquals(
      "/no-such-java-binary",
      resolveLauncherPath(property = "", env = "", processCommand = "/no-such-java-binary"),
    )
    assertNull(resolveLauncherPath(property = null, env = null, processCommand = null))
  }

  @Test
  fun javaBinaryIsNotATarballOrPacmanInstallRoot() {
    // Without -Dklardrop.launcher this is what ProcessHandle reports, and:
    //   linuxInstallRoot("/usr/bin/java") == null  → tarball self-update dies
    //   pacman -Qo /usr/bin/java → jre-openjdk, not klardrop-bin
    assertNull(linuxInstallRoot("/usr/bin/java", home = "/home/x"))
    assertNull(linuxInstallRoot("/usr/lib/jvm/java-21-openjdk/bin/java", home = "/home/x"))
  }

  @Test
  fun userTarballRootIsDetectedFromScriptPathNotJava() {
    val home = "/home/x"
    val script = "$home/.local/lib/klardrop/bin/klardrop"
    assertEquals(
      Path.of(home, ".local", "lib", "klardrop"),
      linuxInstallRoot(script, home = home),
    )
    assertNull(linuxInstallRoot("$home/.local/bin/klardrop", home = home))
    assertNull(linuxInstallRoot("$home/.local/share/klardrop/bin/klardrop", home = home))
  }

  @Test
  fun systemTarballRootIsDetectedFromScriptPath() {
    assertEquals(
      Path.of("/opt/klardrop"),
      linuxInstallRoot("/opt/klardrop/bin/klardrop", home = "/home/x"),
    )
  }

  @Test
  fun currentLauncherPathReadsSystemPropertyNotThisJvmBinary() {
    val previous = System.getProperty(LAUNCHER_PROPERTY)
    val script = "/home/x/.local/lib/klardrop/bin/klardrop"
    try {
      System.setProperty(LAUNCHER_PROPERTY, script)
      val path = currentLauncherPath()
      assertEquals(script, path)
      assertFalse(path!!.contains("java"), "must not report the JVM binary, got: $path")
      assertEquals(
        Path.of("/home/x/.local/lib/klardrop"),
        linuxInstallRoot(path, home = "/home/x"),
      )
    } finally {
      if (previous == null) System.clearProperty(LAUNCHER_PROPERTY)
      else System.setProperty(LAUNCHER_PROPERTY, previous)
    }
  }

  @Test
  fun currentLauncherPathWithoutPropertyIsThisJvmAndNotALinuxInstallRoot() {
    if (!System.getenv(LAUNCHER_ENV).isNullOrBlank()) return
    val previous = System.getProperty(LAUNCHER_PROPERTY)
    try {
      System.clearProperty(LAUNCHER_PROPERTY)
      val path = currentLauncherPath()
      assertTrue(!path.isNullOrBlank(), "test JVM should have a process command")
      checkNotNull(path)
      // Whatever java this test is running on is outside the klardrop app-image.
      assertNull(linuxInstallRoot(path))
      assertNotEquals("/home/x/.local/lib/klardrop/bin/klardrop", path)
    } finally {
      if (previous != null) System.setProperty(LAUNCHER_PROPERTY, previous)
    }
  }

  @Test
  fun resolveLauncherPathFollowsSymlinksWhenTheFileExists() {
    val dir = Files.createTempDirectory("klardrop-launcher")
    try {
      val target = dir.resolve("klardrop")
      Files.writeString(target, "#!/bin/sh\n")
      val link = dir.resolve("link")
      Files.createSymbolicLink(link, target)
      val resolved = resolveLauncherPath(
        property = link.toString(),
        env = "/usr/bin/java",
        processCommand = "/usr/bin/java",
      )
      assertEquals(target.toRealPath().toString(), resolved)
    } finally {
      dir.toFile().deleteRecursively()
    }
  }
}
