import com.carlom.klardrop.common.getAvailableFilePath
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileManagerTest {

  private val testFileSystem = kotlinx.io.files.SystemFileSystem


  @Test
  fun generateNewFilepath() {
    val fileName = "image.jpg"
    val fileName1 = "image (1).jpg"
    val fileName2 = "image (2).jpg"
    val root = Path(SystemTemporaryDirectory, "test-file-manager")

    // ensure folder does not exist
    testFileSystem.deleteRecursively(path = root, mustExist = false)

    // create an empty files
    try {
      testFileSystem.createDirectories(root, mustCreate = true)
      assertTrue { testFileSystem.exists(root) }

      createEmptyFile(Path(root, fileName))
      createEmptyFile(Path(root, fileName1))
      createEmptyFile(Path(root, fileName2))

      val newPath = getAvailableFilePath(root, fileName, testFileSystem)

      assertNotEquals(newPath.name, fileName)
      assertEquals("image (3).jpg", newPath.name)
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