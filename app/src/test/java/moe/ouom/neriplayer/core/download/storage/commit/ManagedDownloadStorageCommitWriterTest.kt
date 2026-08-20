package moe.ouom.neriplayer.core.download.storage.commit

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagedDownloadStorageCommitWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `reuses existing lowercase directory without creating canonical duplicate`() {
        val root = tempFolder.newFolder("download")
        val lowercaseDirectory = File(root, "covers").apply { mkdirs() }

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertEquals(lowercaseDirectory.canonicalFile, resolved.canonicalFile)
        assertEquals(
            1,
            root.listFiles()
                ?.count { it.isDirectory && it.name.equals("Covers", ignoreCase = true) }
        )
    }

    @Test
    fun `prefers exact directory when case variants both exist`() {
        val root = tempFolder.newFolder("download")
        val exactDirectory = File(root, "Covers").apply { mkdirs() }
        File(root, "covers").mkdirs()

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertEquals(exactDirectory.canonicalFile, resolved.canonicalFile)
    }

    @Test
    fun `creates standard directory when no case variant exists`() {
        val root = tempFolder.newFolder("download")

        val resolved = resolveManagedFileSubdirectory(root, "Covers")

        assertTrue(resolved.isDirectory)
        assertEquals(File(root, "Covers").canonicalFile, resolved.canonicalFile)
    }
}
