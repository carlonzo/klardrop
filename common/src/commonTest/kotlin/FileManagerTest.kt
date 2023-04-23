import com.carlom.klardrop.common.getAvailableFilePath
import okio.FileMetadata
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FileManagerTest {

  private val testFileSystem = FileSystemWithExist()

  @Test
  fun generateNewFilepath() {
    val fileName = "image.jpg"
    val root = "/klardrop".toPath()

    testFileSystem.returnExistsFor.addAll(listOf(root.resolve(fileName), root.resolve("image (1).jpg"), root.resolve("image (2).jpg")))

    val newPath = getAvailableFilePath(root, fileName, testFileSystem)

    assertNotEquals(newPath.name, fileName)
    assertEquals("image (3).jpg", newPath.name)
  }


  private class FileSystemWithExist : DefaultFileSystem() {
    var returnExistsFor = mutableSetOf<Path>()

    override fun metadataOrNull(path: Path): FileMetadata? {
      return if (returnExistsFor.contains(path)) {
        FileMetadata()
      } else null
    }
  }
}