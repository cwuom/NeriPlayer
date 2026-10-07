package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.common.storage.directory.ManagedDownloadDirectoryIdentity
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndex
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardReadResult
import moe.ouom.neriplayer.core.download.index.ManagedLibraryFastIndexShardWriteResult
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_INDEX_DIR_NAME
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ManagedDownloadStorageLookupHelpersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context = mock(Context::class.java)

    @Test
    fun `core metadata is durable once finalized or in a durable artifact state`() {
        assertTrue(storage.isDurableCoreMetadata(DownloadedAudioMetadata(downloadFinalized = true)))
        assertTrue(storage.isDurableCoreMetadata(DownloadedAudioMetadata(artifactState = " core_committed ")))
        assertFalse(
            storage.isDurableCoreMetadata(DownloadedAudioMetadata(downloadFinalized = false, artifactState = "STAGED"))
        )
        assertFalse(storage.isDurableCoreMetadata(DownloadedAudioMetadata()))
    }

    @Test
    fun `missing manifest failures carry the shard of a known stable key`() {
        val keyed = storage.missingFastIndexManifestResult("1|netease|")

        assertEquals(ManagedLibraryFastIndex.shardFor("1|netease|"), keyed.shard)
        assertEquals("managed library manifest is unavailable", keyed.error.message)
        assertEquals("", storage.missingFastIndexManifestResult("  ").shard)
    }

    @Test
    fun `fast index root identity distinguishes file and tree roots`() {
        val directory = temporaryFolder.newFolder("library")
        assertEquals(
            "file:${directory.canonicalPath}",
            storage.fastIndexRootIdentity(ManagedDownloadRootHandle.FileRoot(directory))
        )

        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        assertEquals(
            "tree:${ManagedDownloadDirectoryIdentity.directoryIdentity(treeUri)}",
            storage.fastIndexRootIdentity(treeRoot(treeUri))
        )
        assertEquals("tree:", storage.fastIndexRootIdentity(treeRoot("")))
    }

    @Test
    fun `shard storage only touches the root it was created for`() = runTest {
        val root = ManagedDownloadRootHandle.FileRoot(temporaryFolder.newFolder("library"))
        val identity = storage.fastIndexRootIdentity(root)
        val stale = storage.fastIndexShardStorage(context, root, expectedRootIdentity = "file:/previous-root")
        val current = storage.fastIndexShardStorage(context, root, expectedRootIdentity = identity)

        assertTrue(stale.readShard("file:/previous-root", "00") is ManagedLibraryFastIndexShardReadResult.Unavailable)
        assertTrue(stale.writeShard("file:/previous-root", "00", "{}") is ManagedLibraryFastIndexShardWriteResult.Unavailable)
        assertTrue(current.readShard("file:/other-root", "00") is ManagedLibraryFastIndexShardReadResult.Unavailable)
        assertTrue(current.writeShard("file:/other-root", "00", "{}") is ManagedLibraryFastIndexShardWriteResult.Unavailable)
        assertFalse(File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME).exists())

        assertEquals(ManagedLibraryFastIndexShardReadResult.Missing, current.readShard(identity, "00"))
        assertEquals(ManagedLibraryFastIndexShardWriteResult.Written, current.writeShard(identity, "00", "{\"v\":1}"))
        assertEquals(ManagedLibraryFastIndexShardReadResult.Found("{\"v\":1}"), current.readShard(identity, "00"))
        assertEquals(ManagedLibraryFastIndexShardWriteResult.Written, current.writeShard(identity, "00", "{\"v\":2}"))
        assertEquals(
            "{\"v\":2}",
            File(File(root.dir, MANAGED_LIBRARY_INDEX_DIR_NAME), "shard-00.json").readText()
        )
    }

    @Test
    fun `shard writes refuse an index path that is a plain file`() {
        val rootDir = temporaryFolder.newFolder("library")
        File(rootDir, MANAGED_LIBRARY_INDEX_DIR_NAME).writeText("not a directory")

        val result = storage.writeFastIndexShardBlocking(
            context,
            ManagedDownloadRootHandle.FileRoot(rootDir),
            shard = "00",
            payload = "{}"
        )

        assertEquals(
            "fast index path is not a directory",
            (result as ManagedLibraryFastIndexShardWriteResult.Unavailable).error.message
        )
        assertEquals("not a directory", File(rootDir, MANAGED_LIBRARY_INDEX_DIR_NAME).readText())
    }

    @Test
    fun `replacement backups count as missing only inside the target root`() {
        val rootDir = temporaryFolder.newFolder("library")
        val root = ManagedDownloadRootHandle.FileRoot(rootDir)
        val existing = File(rootDir, "existing.mp3").apply { writeText("audio") }
        val outside = temporaryFolder.newFile("outside.mp3")
        outside.delete()

        assertTrue(storage.isMissingReplacementBackup(context, root, backup(File(rootDir, "gone.mp3").absolutePath)))
        assertFalse(storage.isMissingReplacementBackup(context, root, backup(" ${existing.absolutePath} ")))
        assertFalse(storage.isMissingReplacementBackup(context, root, backup(outside.absolutePath)))
    }

    @Test
    fun `documents are inside the managed tree only when the provider path contains it`() {
        val resolver = mock(ContentResolver::class.java)
        val providerContext = mock(Context::class.java)
        `when`(providerContext.contentResolver).thenReturn(resolver)
        val songUri = mock(Uri::class.java)
        val documentPath = mock(DocumentsContract.Path::class.java)
        `when`(documentPath.path).thenReturn(listOf("primary:", "primary:Music", "primary:Music/song.mp3"))

        mockStatic(DocumentsContract::class.java).use { documentsContract ->
            documentsContract.`when`<DocumentsContract.Path?> {
                DocumentsContract.findDocumentPath(resolver, songUri)
            }.thenReturn(documentPath)
            assertTrue(storage.isDocumentWithinManagedTree(providerContext, songUri, "primary:Music"))
            assertFalse(storage.isDocumentWithinManagedTree(providerContext, songUri, "primary:Other"))

            documentsContract.`when`<DocumentsContract.Path?> {
                DocumentsContract.findDocumentPath(resolver, songUri)
            }.thenReturn(null)
            assertFalse(storage.isDocumentWithinManagedTree(providerContext, songUri, "primary:Music"))

            documentsContract.`when`<DocumentsContract.Path?> {
                DocumentsContract.findDocumentPath(resolver, songUri)
            }.thenThrow(IllegalArgumentException("unsupported provider"))
            assertFalse(storage.isDocumentWithinManagedTree(providerContext, songUri, "primary:Music"))

            documentsContract.`when`<DocumentsContract.Path?> {
                DocumentsContract.findDocumentPath(resolver, songUri)
            }.thenThrow(SecurityException("revoked"))
            assertThrows(SecurityException::class.java) {
                storage.isDocumentWithinManagedTree(providerContext, songUri, "primary:Music")
            }
        }
    }

    private fun treeRoot(uriString: String): ManagedDownloadRootHandle.TreeRoot {
        val uri = mock(Uri::class.java)
        `when`(uri.toString()).thenReturn(uriString)
        val tree = mock(DocumentFile::class.java)
        `when`(tree.uri).thenReturn(uri)
        return ManagedDownloadRootHandle.TreeRoot(tree)
    }

    private fun backup(reference: String) = StoredEntry(
        name = File(reference.trim()).name,
        reference = reference,
        mediaUri = reference,
        localFilePath = reference.trim(),
        sizeBytes = 1L,
        lastModifiedMs = 1L
    )
}
