package com.carlom.klardrop.common

import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileManagerTest {

  private val testFileSystem = SystemFileSystem


  @Test
  fun returnsRequestedNameWhenNoCollision() {
    val fileName = "image.jpg"
    val root = Path(SystemTemporaryDirectory, "test-file-manager")

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
    val root = Path(SystemTemporaryDirectory, "test-file-manager")

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
    val root = Path(SystemTemporaryDirectory, "test-file-manager")

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