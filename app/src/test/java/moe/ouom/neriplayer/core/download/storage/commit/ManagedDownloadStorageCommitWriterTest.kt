package moe.ouom.neriplayer.core.download.storage.commit

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

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

    @Test
    fun `planned absent SAF migration target does not enumerate the directory again`() {
        val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
        val resolver = ManagedDownloadCommitMigrationTargetResolver(
            treeChildRegistry = registry,
            tag = "ManagedDownloadStorageCommitWriterTest"
        )
        val source = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "/source/song.mp3",
            mediaUri = "file:///source/song.mp3",
            localFilePath = "/source/song.mp3",
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )

        val resolved = resolver.resolveTreeTarget(
            context = mock(Context::class.java),
            parent = mock(DocumentFile::class.java),
            displayName = source.name,
            sourceEntry = source,
            targetNames = emptySet(),
            targetEntry = null
        )

        assertTrue(resolved.createdNew)
        assertEquals(source.name, resolved.entry.name)
        verifyNoInteractions(registry)
    }

    @Test
    fun `known SAF target entry avoids a second child enumeration`() {
        val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
        val resolver = ManagedDownloadCommitMigrationTargetResolver(
            treeChildRegistry = registry,
            tag = "ManagedDownloadStorageCommitWriterTest"
        )
        val source = ManagedDownloadStorage.StoredEntry(
            name = "song.mp3",
            reference = "content://source/song.mp3",
            mediaUri = "content://source/song.mp3",
            localFilePath = null,
            sizeBytes = 42L,
            lastModifiedMs = 1L
        )
        // 迁移重试时目标索引可能复用同一受信引用, 不需要再次查询目录
        val knownTarget = source.copy()

        val resolved = resolver.resolveTreeTarget(
            context = mock(Context::class.java),
            parent = mock(DocumentFile::class.java),
            displayName = source.name,
            sourceEntry = source,
            targetNames = setOf(source.name),
            targetEntry = knownTarget
        )

        assertTrue(!resolved.createdNew)
        assertEquals(source.name, resolved.entry.name)
        verifyNoInteractions(registry)
    }
}
