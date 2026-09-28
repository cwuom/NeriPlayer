import java.io.File
import org.gradle.api.GradleException
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VerifyCoverageExecutionDataTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `accepts execution data from every participating module`() {
        val app = temporary.newFile("app.exec").apply { writeBytes(byteArrayOf(1)) }
        val library = temporary.newFile("library.exec").apply { writeBytes(byteArrayOf(1)) }
        requireCoverageExecutionData(listOf(app, library))
    }

    @Test
    fun `rejects a missing library even when app data exists`() {
        val app = temporary.newFile("app.exec").apply { writeBytes(byteArrayOf(1)) }
        val missing = File(temporary.root, "missing-library.exec")
        val failure = assertThrows(GradleException::class.java) {
            requireCoverageExecutionData(listOf(app, missing))
        }
        assertTrue(failure.message.orEmpty().contains(missing.path))
    }

    @Test
    fun `rejects empty execution data and directories`() {
        val empty = temporary.newFile("empty-library.exec")
        val directory = temporary.newFolder("not-execution-data")
        val failure = assertThrows(GradleException::class.java) {
            requireCoverageExecutionData(listOf(empty, directory))
        }
        assertTrue(failure.message.orEmpty().contains(empty.path))
        assertTrue(failure.message.orEmpty().contains(directory.path))
    }

    @Test
    fun `rejects an empty module selection`() {
        assertThrows(GradleException::class.java) {
            requireCoverageExecutionData(emptyList())
        }
    }
}
