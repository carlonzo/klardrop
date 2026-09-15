package com.carlom.klardrop.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxWrapperScriptTest {

  @Test
  fun wrapperScriptPassesFakeJavaChecks() {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    if (!os.contains("linux") && !os.contains("nux")) return

    val script = repoRoot().resolve("packaging/linux/test-klardrop-wrapper.sh")
    assertTrue(script.isFile, "missing ${script.absolutePath}")
    val process = ProcessBuilder("bash", script.absolutePath)
      .directory(repoRoot())
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    val code = process.waitFor()
    assertEquals(0, code, "wrapper tests failed:\n$output")
  }

  private fun repoRoot(): File {
    val start = File(System.getProperty("user.dir")).canonicalFile
    return generateSequence(start) { it.parentFile }
      .firstOrNull { File(it, "packaging/linux/klardrop").isFile }
      ?: error("could not find repo root from $start")
  }
}
