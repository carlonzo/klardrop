package com.carlom.klardrop.common

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileManagerTest {

  private val testFileSystem = SystemFileSystem

  // Unique temp root per call so concurrently-running test tasks (desktopJvmTest,
  // testAndroidHostTest, testAndroid all execute these commonTest classes) don't share a
  // fixed directory under SystemTemporaryDirectory and race each other's create/delete —
  // which intermittently made exists() checks fail on CI.
  private fun uniqueRoot(name: String): Path =
    Path(SystemTemporaryDirectory, "$name-${Random.nextLong().toULong().toString(16)}")


  @Test
  fun returnsRequestedNameWhenNoCollision() {
    val fileName = "image.jpg"
    val root = uniqueRoot("test-file-manager")

    testFileSystem.deleteRecursively(path = root, mustExist = false)

    try {
      testFileSystem.createDirectories(root, mustCreate = true)
      assertTrue { testFileSystem.exists(root) }

      val newPath = getAvailableFilePath(root, fileName, testFileSystem)

      assertEquals(fileName, newPath.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun appendsCounterBeforeExtensionOnCollision() {
    val fileName = "dog.jpeg"
    val root = uniqueRoot("test-file-manager")

    // ensure folder does not exist
    testFileSystem.deleteRecursively(path = root, mustExist = false)

    // create an empty files
    try {
      testFileSystem.createDirectories(root, mustCreate = true)
      assertTrue { testFileSystem.exists(root) }

      createEmptyFile(Path(root, fileName))
      createEmptyFile(Path(root, "dog-1.jpeg"))
      createEmptyFile(Path(root, "dog-2.jpeg"))

      val newPath = getAvailableFilePath(root, fileName, testFileSystem)

      assertNotEquals(newPath.name, fileName)
      assertEquals("dog-3.jpeg", newPath.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun appendsCounterForFileWithoutExtension() {
    val fileName = "README"
    val root = uniqueRoot("test-file-manager")

    testFileSystem.deleteRecursively(path = root, mustExist = false)

    try {
      testFileSystem.createDirectories(root, mustCreate = true)
      createEmptyFile(Path(root, fileName))

      val newPath = getAvailableFilePath(root, fileName, testFileSystem)

      assertEquals("README-1", newPath.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }


  // ---- sanitizeFileName unit tests (no filesystem required) --------------------------------

  @Test
  fun sanitizeFileName_plainNamePassesThrough() {
    assertEquals("image.jpg", sanitizeFileName("image.jpg"))
  }

  @Test
  fun sanitizeFileName_relativeTraversalIsStripped() {
    // "../../evil.txt" must resolve to just "evil.txt"
    assertEquals("evil.txt", sanitizeFileName("../../evil.txt"))
  }

  @Test
  fun sanitizeFileName_singleDotTraversalIsStripped() {
    assertEquals("evil.txt", sanitizeFileName("../evil.txt"))
  }

  @Test
  fun sanitizeFileName_embeddedSlashKeepsLastSegment() {
    // "a/b/c.txt" should yield "c.txt" — the subdirectory components are stripped
    assertEquals("c.txt", sanitizeFileName("a/b/c.txt"))
  }

  @Test
  fun sanitizeFileName_windowsBackslashSeparatorsAreStripped() {
    assertEquals("evil.txt", sanitizeFileName("..\\..\\evil.txt"))
  }

  @Test
  fun sanitizeFileName_windowsMixedSeparators() {
    assertEquals("c.txt", sanitizeFileName("a\\b/c.txt"))
  }

  @Test
  fun sanitizeFileName_emptyNameFallsBackToDefault() {
    assertEquals("file", sanitizeFileName(""))
  }

  @Test
  fun sanitizeFileName_singleDotFallsBackToDefault() {
    assertEquals("file", sanitizeFileName("."))
  }

  @Test
  fun sanitizeFileName_doubleDotFallsBackToDefault() {
    assertEquals("file", sanitizeFileName(".."))
  }

  @Test
  fun sanitizeFileName_absoluteUnixPathKeepsBasename() {
    // "/etc/passwd" -> "passwd"
    assertEquals("passwd", sanitizeFileName("/etc/passwd"))
  }

  @Test
  fun sanitizeFileName_trailingSlashFallsBackToDefault() {
    // "evil/" ends with separator; last segment is empty
    assertEquals("file", sanitizeFileName("evil/"))
  }

  @Test
  fun sanitizeFileName_customDefaultUsedForBadNames() {
    assertEquals("fallback", sanitizeFileName("..", default = "fallback"))
  }

  // ---- getAvailableFilePath security tests (uses real filesystem) ---------------------------

  @Test
  fun getAvailableFilePath_traversalFileNameStaysInsideParent() {
    val root = uniqueRoot("test-security-traversal")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)

      val result = getAvailableFilePath(root, "../../evil.txt", testFileSystem)

      // The result must be strictly inside root
      val resolvedRoot = testFileSystem.resolve(root).toString()
      assertTrue(
        result.toString().startsWith("$resolvedRoot/"),
        "Expected result inside $resolvedRoot but got $result"
      )
      // The filename must be the bare name, not containing separators
      assertFalse(result.name.contains('/'), "Result name must not contain '/'")
      assertFalse(result.name.contains('\\'), "Result name must not contain '\\'")
      assertEquals("evil.txt", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun getAvailableFilePath_embeddedSeparatorsStayInsideParent() {
    val root = uniqueRoot("test-security-embedded")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)

      val result = getAvailableFilePath(root, "a/b/c.txt", testFileSystem)

      val resolvedRoot = testFileSystem.resolve(root).toString()
      assertTrue(
        result.toString().startsWith("$resolvedRoot/"),
        "Expected result inside $resolvedRoot but got $result"
      )
      assertEquals("c.txt", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun getAvailableFilePath_dotNameFallsBackToSafeName() {
    val root = uniqueRoot("test-security-dot")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)

      val result = getAvailableFilePath(root, "..", testFileSystem)

      val resolvedRoot = testFileSystem.resolve(root).toString()
      assertTrue(
        result.toString().startsWith("$resolvedRoot/"),
        "Expected result inside $resolvedRoot but got $result"
      )
      assertEquals("file", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun getAvailableFilePath_emptyNameFallsBackToSafeName() {
    val root = uniqueRoot("test-security-empty")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)

      val result = getAvailableFilePath(root, "", testFileSystem)

      val resolvedRoot = testFileSystem.resolve(root).toString()
      assertTrue(
        result.toString().startsWith("$resolvedRoot/"),
        "Expected result inside $resolvedRoot but got $result"
      )
      assertEquals("file", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun getAvailableFilePath_windowsBackslashTraversalStaysInsideParent() {
    val root = uniqueRoot("test-security-backslash")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)

      val result = getAvailableFilePath(root, "..\\..\\evil.txt", testFileSystem)

      val resolvedRoot = testFileSystem.resolve(root).toString()
      assertTrue(
        result.toString().startsWith("$resolvedRoot/"),
        "Expected result inside $resolvedRoot but got $result"
      )
      assertEquals("evil.txt", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  @Test
  fun getAvailableFilePath_sanitisedNamePreservesDeduplication() {
    // Even after sanitisation, the collision-counter logic must still work
    val root = uniqueRoot("test-security-dedup")
    testFileSystem.deleteRecursively(path = root, mustExist = false)
    try {
      testFileSystem.createDirectories(root, mustCreate = true)
      // Create the base collision file using the sanitised name
      createEmptyFile(Path(root, "evil.txt"))

      val result = getAvailableFilePath(root, "../../evil.txt", testFileSystem)

      assertEquals("evil-1.txt", result.name)
    } finally {
      testFileSystem.deleteRecursively(path = root, mustExist = false)
    }
  }

  private fun FileSystem.deleteRecursively(path: Path, mustExist: Boolean = false) {
    if (!exists(path)) {
      if (mustExist) throw IOException("Path at $path does not exist")
      else return
    }

    if (metadataOrNull(path)?.isDirectory == true) {
      list(path).forEach { deleteRecursively(it) }
    }

    if (exists(path)){
      delete(path)
    }
  }

  private fun createEmptyFile(path: Path) {
    testFileSystem.sink(path).buffered().use { it.writeInt(0) }

    assertTrue { testFileSystem.exists(path) }
  }
}