package moe.ouom.neriplayer.core.download.storage.operation.content

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.StoredEntry
import moe.ouom.neriplayer.core.download.storage.MANAGED_LIBRARY_MANIFEST_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.delete.ManagedDownloadDeletePolicy
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.data.model.download.storage.StorageMutationResult
import moe.ouom.neriplayer.data.model.download.storage.StorageReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadStorageReferenceTrustTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val storage = ManagedDownloadStorage
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        storage.clearTreeDirectoryCache()
        storage.invalidateSnapshotCache(context)
    }

    @Test
    fun `trusted references classify content, file uri and plain paths`() {
        val content = storage.trustedManagedRef("content://com.example.docs/document/primary%3ASong.mp3")
        val fileUri = storage.trustedManagedRef("file:///music/Song.mp3")
        val path = storage.trustedManagedRef("/music/Song.mp3")

        assertEquals(StorageReference.SafRef("content://com.example.docs/document/primary%3ASong.mp3".toUri()), content.reference)
        assertEquals(StorageReference.FileRef("/music/Song.mp3"), fileUri.reference)
        assertEquals("file:///music/Song.mp3", fileUri.externalReference)
        assertEquals(StorageReference.FileRef("/music/Song.mp3"), path.reference)

        assertNull(storage.trustedManagedRefOrNull(null))
        assertNull(storage.trustedManagedRefOrNull("   "))
        assertEquals("/music/Song.mp3", storage.trustedManagedRefOrNull(" /music/Song.mp3 ")?.externalReference)
    }

    @Test
    fun `trusted reference resolution keeps snapshot trust and managed file paths only`() {
        val root = temporaryFolder.newFolder("library").absolutePath
        val trustedContent = "content://com.example.docs/document/primary%3ATrusted.mp3"
        val policy = ManagedDownloadDeletePolicy(
            managedFileRoots = listOf(root),
            managedTreeRoots = emptyList(),
            trustedReferences = setOf(storage.trustedManagedRef(trustedContent))
        )

        val resolved = storage.resolveTrustedManagedReferences(
            listOf(
                trustedContent,
                "content://com.example.docs/document/primary%3AUntrusted.mp3",
                "$root/Song.mp3",
                "$root/Song.mp3",
                "/elsewhere/Song.mp3",
                null,
                " "
            ),
            policy
        )

        assertEquals(listOf(trustedContent, "$root/Song.mp3"), resolved.map { it.externalReference })
    }

    @Test
    fun `restoring modification time only touches existing local files`() {
        val file = temporaryFolder.newFile("Song.mp3")
        val entry = StoredEntry(file.name, file.absolutePath, file.absolutePath, file.absolutePath, 0L, 0L)
        val remote = StoredEntry("Song.mp3", "content://docs/1", "content://docs/1", null, 0L, 0L)

        storage.restoreStoredEntryLastModified(entry, 1_600_000_000_000L)
        assertEquals(1_600_000_000_000L, file.lastModified())

        storage.restoreStoredEntryLastModified(entry, 0L)
        storage.restoreStoredEntryLastModified(remote, 1_700_000_000_000L)
        assertEquals(1_600_000_000_000L, file.lastModified())
    }

    @Test
    fun `migration delete failures are classified from the current reference state`() {
        val existing = temporaryFolder.newFile("Kept.mp3")
        val missing = File(temporaryFolder.root, "Gone.mp3")

        assertEquals(
            StorageMutationResult.Missing,
            storage.classifyMigrationDeleteFailure(context, storage.trustedManagedRef(missing.absolutePath))
        )
        val accessible = storage.classifyMigrationDeleteFailure(context, storage.trustedManagedRef(existing.absolutePath))
        assertTrue(accessible is StorageMutationResult.ProviderFailure)
    }

    @Test
    fun `enumerated migration deletes require the reference to be part of the root listing`() = runTest {
        val rootDir = temporaryFolder.newFolder("source")
        val audio = File(rootDir, "Song.mp3").apply { writeText("audio") }
        val outside = temporaryFolder.newFile("Outside.mp3")
        val root = ManagedDownloadRootHandle.FileRoot(rootDir)

        assertEquals(
            StorageMutationResult.OutOfScope,
            storage.deleteEnumeratedMigrationReference(context, storage.trustedManagedRef(outside.absolutePath), root)
        )
        assertTrue(outside.exists())

        assertEquals(
            StorageMutationResult.Deleted,
            storage.deleteEnumeratedMigrationReference(context, storage.trustedManagedRef(audio.absolutePath), root)
        )
        assertFalse(audio.exists())
    }

    @Test
    fun `library manifest is created once and malformed manifests are ignored`() {
        val rootDir = temporaryFolder.newFolder("managed")
        val root = ManagedDownloadRootHandle.FileRoot(rootDir)
        val manifest = File(rootDir, MANAGED_LIBRARY_MANIFEST_FILE_NAME)

        assertNull(storage.findManagedLibraryManifestEntry(context, root))
        assertNull(storage.readManagedLibraryIdBlocking(context, root))

        val libraryId = storage.ensureManagedLibraryManifestBlocking(context, root)
        assertEquals(libraryId, JSONObject(manifest.readText()).getString("libraryId"))
        assertEquals(manifest.absolutePath, storage.findManagedLibraryManifestEntry(context, root)?.reference)
        assertEquals(libraryId, storage.readManagedLibraryIdBlocking(context, root))
        assertEquals(libraryId, storage.ensureManagedLibraryManifestBlocking(context, root))

        manifest.writeText("{broken")
        assertNull(storage.readManagedLibraryIdBlocking(context, root))
        manifest.writeText("""{"libraryId":"  "}""")
        assertNull(storage.readManagedLibraryIdBlocking(context, root))
    }

    @Test
    fun `manifest creation fails when the root cannot be written`() {
        val blocked = temporaryFolder.newFile("not-a-directory")
        val root = ManagedDownloadRootHandle.FileRoot(blocked)

        assertThrows(IOException::class.java) {
            storage.ensureManagedLibraryManifestBlocking(context, root)
        }
    }
}
