package moe.ouom.neriplayer.core.download

import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import moe.ouom.neriplayer.core.download.storage.backend.StorageMutationResult
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeChildRegistry
import moe.ouom.neriplayer.core.download.storage.tree.ManagedDownloadTreeDirectories
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadNoMediaInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val treeUri = DocumentsContract.buildTreeDocumentUri(
        ManagedDownloadMigrationTestDocumentProvider.AUTHORITY,
        ManagedDownloadMigrationTestDocumentProvider.ROOT_ID
    )

    @Before
    @After
    fun reset() {
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
    }

    @Test
    fun platformExternalStorageProviderKeepsOneMarkerDuringParallelPreparation() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        var fixture: DocumentFile? = null
        val executor = Executors.newFixedThreadPool(8)
        try {
            val platformTree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Download")
            val root = requireNotNull(DocumentFile.fromTreeUri(context, platformTree))
            val fixtureName = "neriplayer-marker-test-${UUID.randomUUID()}"
            requireNotNull(root.createDirectory(fixtureName))
            fixture = root.listFiles().single { it.name == fixtureName }
            requireNotNull(fixture.createDirectory(".tmp"))
            val directory = fixture.listFiles().single { it.name == ".tmp" }
            val futures = List(16) {
                executor.submit {
                    directories().ensureManagedMediaScanIsolation(context, ".tmp", directory)
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(".nomedia"), directory.listFiles().map { it.name })
        } finally {
            executor.shutdownNow()
            try {
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
                fixture?.let { assertTrue("remove this test's public directory", it.delete()) }
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
    }

    @Test
    fun parallelWritersKeepOneExactMarkerAcrossDirectoryCacheInstances() {
        val directory = createDirectory()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(16) {
                executor.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    directories().ensureManagedMediaScanIsolation(context, ".tmp", directory)
                }
            }
            start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            assertEquals(listOf(".nomedia"), directory.listFiles().map { it.name })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun fallbackRenameUsesTheNewDocumentIdentity() {
        val directory = prepareRename("exact")
        directories().ensureManagedMediaScanIsolation(context, ".tmp", directory)
        assertEquals(listOf(".nomedia"), directory.listFiles().map { it.name })
        val marker = requireNotNull(directory.findFile(".nomedia"))
        assertEquals(0, requireNotNull(context.contentResolver.openInputStream(marker.uri)).use { it.readBytes().size })
    }

    @Test
    fun existingExactMarkerAllowsRemovingOnlyEmptyNumberedMarkers() {
        val directory = createDirectory()
        listOf(".nomedia", " (1).nomedia").forEach { name ->
            val marker = requireNotNull(directory.createFile("application/octet-stream", name))
            requireNotNull(context.contentResolver.openOutputStream(marker.uri, "w")).close()
        }
        val nonempty = requireNotNull(directory.createFile("application/octet-stream", " (2).nomedia"))
        requireNotNull(context.contentResolver.openOutputStream(nonempty.uri, "w")).use { it.write(7) }
        requireNotNull(directory.createDirectory(" (3).nomedia"))
        requireNotNull(directory.createFile("application/octet-stream", "other.pending"))

        directories().ensureManagedMediaScanIsolation(context, ".tmp", directory)

        assertEquals(setOf(".nomedia", " (2).nomedia", " (3).nomedia", "other.pending"),
            directory.listFiles().map { it.name }.toSet())
        assertEquals(7, requireNotNull(context.contentResolver.openInputStream(nonempty.uri)).use { it.read() })
    }

    @Test
    fun fallbackRenameCollisionDoesNotLeaveNumberedMarkersOrDeleteOldUris() {
        val directory = prepareRename("collision")
        val deletedNames = mutableListOf<String?>()
        directories { reference ->
            val document = requireNotNull(DocumentFile.fromSingleUri(context, android.net.Uri.parse(reference)))
            deletedNames += document.name
            assertTrue("cleanup must use the renamed document URI", document.exists())
        }.ensureManagedMediaScanIsolation(context, ".tmp", directory)
        assertTrue("provider cannot provide an exact marker, so no candidate may remain", directory.listFiles().isEmpty())
        assertTrue(deletedNames.isNotEmpty())
        assertTrue(deletedNames.all { it == " (1).nomedia" })
    }

    private fun prepareRename(mode: String): DocumentFile {
        val directory = createDirectory()
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.NO_MEDIA_RENAME, mode, null)
        return directory
    }

    private fun createDirectory(): DocumentFile {
        val root = requireNotNull(DocumentFile.fromTreeUri(context, treeUri))
        requireNotNull(root.createDirectory(".tmp"))
        return root.listFiles().single { it.name == ".tmp" }
    }

    private fun directories(beforeDelete: (String) -> Unit = {}) = ManagedDownloadTreeDirectories(
        treeChildRegistry = ManagedDownloadTreeChildRegistry(
            writeCacheValidateIntervalMs = 0L,
            treeCacheValidateIntervalMs = 0L,
            treeWriteCacheValidateIntervalMs = 0L,
            onTreeQueryFailed = {}
        ),
        tag = "NoMediaTest",
        deleteTrustedReference = { _, reference ->
            beforeDelete(reference.externalReference)
            if (DocumentsContract.deleteDocument(context.contentResolver, android.net.Uri.parse(reference.externalReference))) {
                StorageMutationResult.Deleted
            } else {
                StorageMutationResult.Missing
            }
        }
    )
}
