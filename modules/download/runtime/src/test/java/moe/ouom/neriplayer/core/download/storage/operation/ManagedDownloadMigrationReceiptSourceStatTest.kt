package moe.ouom.neriplayer.core.download.storage.operation

import android.content.Context
import java.io.File
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import moe.ouom.neriplayer.data.model.download.storage.StorageLookupResult
import moe.ouom.neriplayer.data.model.download.storage.StorageStat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class ManagedDownloadMigrationReceiptSourceStatTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mock(Context::class.java)

    @Test
    fun `sources outside the migration root are out of scope even with a shared name prefix`() = runTest {
        val root = temporaryFolder.newFolder("root")
        val outside = File(temporaryFolder.newFolder("root-other"), "song.flac").apply { writeText("audio") }

        assertEquals(StorageLookupResult.OutOfScope, stat(root, outside.absolutePath))
    }

    @Test
    fun `sources inside the migration root are stated through the file backend`() = runTest {
        val root = temporaryFolder.newFolder("root")
        val source = File(root, "song.flac").apply { writeText("audio") }

        val found = stat(root, " ${source.absolutePath} ") as StorageLookupResult.Found<StorageStat>

        assertEquals("song.flac", found.value.displayName)
        assertEquals(5L, found.value.sizeBytes)
        assertEquals(StorageLookupResult.Missing, stat(root, File(root, "gone.flac").absolutePath))
    }

    private suspend fun stat(root: File, reference: String) =
        ManagedDownloadStorage.statMigrationReceiptSource(
            context = context,
            sourceRoot = ManagedDownloadRootHandle.FileRoot(root),
            sourceEntry = ManagedDownloadStorage.StoredEntry(
                name = File(reference.trim()).name,
                reference = reference,
                mediaUri = "",
                localFilePath = null,
                sizeBytes = 0L,
                lastModifiedMs = 0L
            )
        )
}
