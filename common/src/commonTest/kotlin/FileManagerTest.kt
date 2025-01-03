import com.carlom.klardrop.common.getAvailableFilePath
import kotlinx.io.buffered
import kotlinx.io.files.Path
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
    val root = Path("/tmp/testfilemanger1")

    // create an empty files
    try {
      testFileSystem.createDirectories(root)
      createEmptyFile(Path(root, fileName))
      createEmptyFile(Path(root, fileName1))
      createEmptyFile(Path(root, fileName2))

      val newPath = getAvailableFilePath(root, fileName, testFileSystem)

      assertNotEquals(newPath.name, fileName)
      assertEquals("image (3).jpg", newPath.name)
    } finally {
      testFileSystem.list(root).forEach { testFileSystem.delete(it,  mustExist = false) }
      testFileSystem.delete(root, mustExist = false)
    }

  }

  private fun createEmptyFile(path: Path) {
    testFileSystem.sink(path).buffered().use { it.writeInt(0) }

    assertTrue { testFileSystem.exists(path) }
  }
}