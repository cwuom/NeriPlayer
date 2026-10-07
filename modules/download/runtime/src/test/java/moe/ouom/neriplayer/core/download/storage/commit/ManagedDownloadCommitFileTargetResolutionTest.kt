package moe.ouom.neriplayer.core.download.storage.commit

import java.io.File
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class ManagedDownloadCommitFileTargetResolutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val registry = mock(ManagedDownloadTreeChildRegistry::class.java)
    private val resolver = ManagedDownloadCommitMigrationTargetResolver(
        treeChildRegistry = registry,
        tag = "ManagedDownloadCommitFileTargetResolutionTest"
    )

    @Test
    fun `a listed target that already is the source file is reused without reserving a name`() {
        val parent = temporaryFolder.root
        val existing = File(parent, "Song.flac").apply { writeText("audio") }

        val resolved = resolver.resolveFileTarget(
            parent = parent,
            displayName = existing.name,
            sourceEntry = entry(existing.name, existing.absolutePath),
            targetNames = setOf(existing.name, "Missing.flac")
        )

        assertFalse(resolved.createdNew)
        assertEquals(existing.absolutePath, resolved.entry.reference)
        verifyNoInteractions(registry)
    }

    @Test
    fun `an unlisted source file on disk is reused after reading it directly`() {
        val parent = temporaryFolder.root
        val existing = File(parent, "Song.flac").apply { writeText("audio") }

        val resolved = resolver.resolveFileTarget(
            parent = parent,
            displayName = existing.name,
            sourceEntry = entry(existing.name, existing.absolutePath),
            targetNames = setOf("Other.flac")
        )

        assertFalse(resolved.createdNew)
        assertEquals(existing.absolutePath, resolved.entry.reference)
        verifyNoInteractions(registry)
    }

    @Test
    fun `a directory occupying the target name forces a reserved replacement name`() {
        val parent = temporaryFolder.root
        File(parent, "Folder.flac").mkdirs()
        `when`(registry.reserveUniqueFileChildName(parent, "Folder.flac")).thenReturn("Folder (1).flac")

        val resolved = resolver.resolveFileTarget(
            parent = parent,
            displayName = "Folder.flac",
            sourceEntry = entry("Folder.flac", "/old/Folder.flac"),
            targetNames = setOf("Other.flac")
        )

        assertTrue(resolved.createdNew)
        assertEquals("Folder (1).flac", resolved.entry.name)
        verify(registry).reserveUniqueFileChildName(parent, "Folder.flac")
    }

    @Test
    fun `metadata from another source reserves a new name with or without listed candidates`() {
        val parent = temporaryFolder.root
        val listedMetadata = File(parent, "Song.flac.npmeta.json").apply { writeText("{}") }
        `when`(registry.reserveUniqueFileChildName(parent, listedMetadata.name))
            .thenReturn("Song (1).flac.npmeta.json")
        `when`(registry.reserveUniqueFileChildName(parent, "Other.flac.npmeta.json"))
            .thenReturn("Other.flac.npmeta.json")

        val replaced = resolver.resolveFileTarget(
            parent = parent,
            displayName = listedMetadata.name,
            sourceEntry = entry(listedMetadata.name, "/old/${listedMetadata.name}"),
            targetNames = setOf(listedMetadata.name)
        )
        val fresh = resolver.resolveFileTarget(
            parent = parent,
            displayName = "Other.flac.npmeta.json",
            sourceEntry = entry("Other.flac.npmeta.json", "/old/Other.flac.npmeta.json"),
            targetNames = setOf(listedMetadata.name)
        )

        assertTrue(replaced.createdNew)
        assertEquals("Song (1).flac.npmeta.json", replaced.entry.name)
        assertTrue(fresh.createdNew)
        assertEquals("Other.flac.npmeta.json", fresh.entry.name)
    }

    private fun entry(name: String, reference: String) = ManagedDownloadStorage.StoredEntry(
        name = name,
        reference = reference,
        mediaUri = "file://$reference",
        localFilePath = reference,
        sizeBytes = 5L,
        lastModifiedMs = 1L
    )
}
