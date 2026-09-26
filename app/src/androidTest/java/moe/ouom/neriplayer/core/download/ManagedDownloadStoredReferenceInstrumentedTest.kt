package moe.ouom.neriplayer.core.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.storage.entry.ManagedDownloadStoredEntryMapper
import moe.ouom.neriplayer.core.download.storage.operation.lifecycle.composeSnapshot
import moe.ouom.neriplayer.core.download.storage.operation.resolveRootBlocking
import moe.ouom.neriplayer.core.download.storage.recovery.ManagedDownloadPendingAudioWriteNames
import moe.ouom.neriplayer.core.download.storage.root.ManagedDownloadRootHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedDownloadStoredReferenceInstrumentedTest {
    @Test
    fun coldPrivateReferenceDoesNotBuildSnapshot() = runBlocking {
        withStorage(false) {
            val audio = createAudio("real %20 name.mp3")
            val result = ManagedDownloadStorage.queryStoredEntry(context, audio.reference)
            assertEquals(audio.name, result?.name)
            assertEquals(audio.reference, result?.reference)
            assertNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun coldSafReferenceUsesDisplayNameWithoutDirectoryEnumeration() = runBlocking {
        withStorage(true) {
            val audio = createAudio("real %20 name.mp3")
            assertFalse(audio.reference.contains("real"))
            val before = counters()
            val result = ManagedDownloadStorage.queryStoredEntry(context, audio.reference)
            assertEquals(audio.name, result?.name)
            val returnedUri = Uri.parse(requireNotNull(result).reference)
            assertEquals(AUTHORITY, returnedUri.authority)
            assertEquals(DocumentsContract.getDocumentId(Uri.parse(audio.reference)), DocumentsContract.getDocumentId(returnedUri))
            assertEquals(DocumentsContract.getTreeDocumentId(treeUri), DocumentsContract.getTreeDocumentId(returnedUri))
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
            assertNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun coldSafSingleDocumentReferenceIsResolvedWithinConfiguredTree() = runBlocking {
        withStorage(true) {
            val audio = createAudio("single reference.mp3")
            val single = DocumentsContract.buildDocumentUri(AUTHORITY, DocumentsContract.getDocumentId(Uri.parse(audio.reference)))
            val before = counters()
            val result = ManagedDownloadStorage.queryStoredEntry(context, single.toString())
            assertNotNull(result)
            assertEquals(audio.name, result?.name)
            assertEquals(DocumentsContract.getDocumentId(Uri.parse(audio.reference)), DocumentsContract.getDocumentId(Uri.parse(result!!.reference)))
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
        }
    }

    @Test
    fun coldPrivatePendingReferencePreservesTheLogicalAudioName() = runBlocking {
        withStorage(false) {
            val audio = createPendingAudio()
            val result = ManagedDownloadStorage.queryStoredEntry(context, audio.reference)
            assertTrue(result?.isPendingAudioWrite == true)
            assertEquals("pending song.mp3", result?.logicalName)
            assertNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun coldSafPendingReferencePreservesTheLogicalAudioName() = runBlocking {
        withStorage(true) {
            val audio = createPendingAudio()
            val before = counters()
            val result = ManagedDownloadStorage.queryStoredEntry(context, audio.reference)
            assertTrue(result?.isPendingAudioWrite == true)
            assertEquals("pending song.mp3", result?.logicalName)
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
        }
    }

    @Test
    fun warmPrivateMissingReferenceKeepsSnapshotWithoutRebuildingIt() = runBlocking {
        withStorage(false) {
            val audio = createPendingAudio()
            assertTrue(audio.isPendingAudioWrite)
            warm(audio)
            val snapshot = ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)
            assertTrue(File(audio.reference).delete())
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, audio.reference))
            assertSame(snapshot, ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun warmSafMissingReferenceDoesNotRefreshTheDirectory() = runBlocking {
        withStorage(true) {
            val audio = createPendingAudio()
            assertTrue(audio.isPendingAudioWrite)
            warm(audio)
            val snapshot = ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(audio.reference))
            val before = counters()
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, audio.reference))
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
            assertSame(snapshot, ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun coldSafMissingReferenceDoesNotRefreshTheDirectory() = runBlocking {
        withStorage(true) {
            val missing = DocumentsContract.buildDocumentUriUsingTree(treeUri, "missing-${UUID.randomUUID()}")
            val before = counters()
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, missing.toString()))
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
            assertNull(ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
    }

    @Test
    fun warmSafUnknownReferenceKeepsNullableContractWithoutDirectoryRefresh() = runBlocking {
        withStorage(true) {
            val audio = createAudio("unavailable.mp3")
            warm(audio)
            assertUnknownDoesNotEnumerate(audio)
        }
    }

    @Test
    fun coldSafUnknownReferenceKeepsNullableContractWithoutDirectoryRefresh() = runBlocking {
        withStorage(true) {
            val audio = createAudio("unavailable.mp3")
            assertUnknownDoesNotEnumerate(audio)
        }
    }

    @Test
    fun privateOutsidePathAndSymlinkCannotBeClaimed() = runBlocking {
        withStorage(false) {
            val outside = File(context.cacheDir, "outside.mp3").apply { writeBytes(PAYLOAD) }
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, outside.absolutePath))
            val rootDirectory = (root as ManagedDownloadRootHandle.FileRoot).dir
            val link = File(rootDirectory, "outside-link.mp3")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, link.absolutePath))
            assertTrue(outside.readBytes().contentEquals(PAYLOAD))
        }
    }

    @Test
    fun safOtherRootAndForgedTreePrefixCannotBeClaimed() = runBlocking {
        withStorage(true) {
            val otherTree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ManagedDownloadMigrationTestDocumentProvider.SOURCE_ROOT_ID)
            val otherRoot = requireNotNull(DocumentFile.fromTreeUri(context, otherTree))
            val outside = requireNotNull(otherRoot.createFile("audio/mpeg", "outside.mp3"))
            context.contentResolver.openOutputStream(outside.uri, "w")!!.use { it.write(PAYLOAD) }
            val forged = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(outside.uri))
            val before = counters()
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, outside.uri.toString()))
            assertNull(ManagedDownloadStorage.queryStoredEntry(context, forged.toString()))
            assertEquals(0, counters().getInt("count") - before.getInt("count"))
            assertTrue(context.contentResolver.openInputStream(outside.uri)!!.use { it.readBytes() }.contentEquals(PAYLOAD))
        }
    }

    @Test
    fun exactSafLookupQueryCountDoesNotGrowWithLibrarySize() = runBlocking {
        withStorage(true) {
            val audio = createAudio("chosen.mp3")
            var count = 0
            val costs = listOf(0, 64, 512).map { total ->
                while (count < total) createAudio("background-${count++}.mp3")
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.treeChildRegistry.clear()
                val before = counters()
                repeat(8) { assertNotNull(ManagedDownloadStorage.queryStoredEntry(context, audio.reference)) }
                val after = counters()
                assertEquals("exact lookups must not enumerate $total siblings", 0, after.getInt("count") - before.getInt("count"))
                val rootQueries = after.getInt("rootDocumentQueries") - before.getInt("rootDocumentQueries")
                assertTrue("root permission revalidation stays bounded per lookup", rootQueries <= 8)
                (after.getInt("documentQueries") - before.getInt("documentQueries") - rootQueries) to
                    (after.getInt("documentPaths") - before.getInt("documentPaths"))
            }
            assertEquals(costs.first(), costs[1])
            assertEquals(costs.first(), costs[2])
        }
    }

    private suspend fun Fixture.assertUnknownDoesNotEnumerate(audio: ManagedDownloadStorage.StoredEntry) {
        val snapshot = ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false)
        for (fault in listOf("null", "permission", "failure")) {
            context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.REFERENCE_QUERY_FAULT, audio.reference, Bundle().apply { putString("fault", fault) })
            val before = counters()
            assertNull("unknown reference retains nullable lookup contract", ManagedDownloadStorage.queryStoredEntry(context, audio.reference))
            val after = counters()
            assertTrue("fault must reach exact-reference query", after.getInt("referenceQueryFaults") > 0)
            assertEquals(0, after.getInt("count") - before.getInt("count"))
            assertSame(snapshot, ManagedDownloadStorage.snapshotCacheStore.cachedSnapshot(context, restorePersisted = false))
        }
        context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.REFERENCE_QUERY_FAULT, null, null)
        assertTrue(context.contentResolver.openInputStream(Uri.parse(audio.reference))!!.use { it.readBytes() }.contentEquals(PAYLOAD))
    }

    private suspend fun withStorage(saf: Boolean, block: suspend Fixture.() -> Unit) =
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val previousDirectory = ManagedDownloadStorage.configuredDirectoryUri()
            val directory = File(base.cacheDir, "stored-reference-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getExternalFilesDir(type: String?): File = File(directory, type ?: "external").apply { mkdirs() }
                override fun getFilesDir(): File = File(directory, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir(): File = File(directory, "noBackup").apply { mkdirs() }
                override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
            }
            val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ManagedDownloadMigrationTestDocumentProvider.ROOT_ID)
            if (saf) context.contentResolver.call(tree, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
            ManagedDownloadStorage.primeSettings(if (saf) tree.toString() else null, null)
            ManagedDownloadStorage.snapshotCacheStore.invalidate()
            ManagedDownloadStorage.treeChildRegistry.clear()
            try {
                Fixture(context, tree, ManagedDownloadStorage.resolveRootBlocking(context)).block()
            } finally {
                ManagedDownloadStorage.primeSettings(previousDirectory, null)
                ManagedDownloadStorage.snapshotCacheStore.invalidate()
                ManagedDownloadStorage.treeChildRegistry.clear()
                if (saf) context.contentResolver.call(tree, ManagedDownloadMigrationTestDocumentProvider.RESET, null, null)
                directory.deleteRecursively()
            }
        }

    private class Fixture(val context: Context, val treeUri: Uri, val root: ManagedDownloadRootHandle) {
        fun createPendingAudio(): ManagedDownloadStorage.StoredEntry {
            val name = ManagedDownloadPendingAudioWriteNames().buildPendingAudioWriteName("pending song.mp3")
            return when (root) {
                is ManagedDownloadRootHandle.FileRoot -> {
                    val temporary = File(root.dir, ".tmp").apply { mkdirs() }
                    ManagedDownloadStoredEntryMapper.fromFile(File(temporary, name).apply { writeBytes(PAYLOAD) })
                }
                is ManagedDownloadRootHandle.TreeRoot -> {
                    val temporary = requireNotNull(root.tree.createDirectory(".tmp"))
                    val document = requireNotNull(temporary.createFile("application/octet-stream", name))
                    context.contentResolver.openOutputStream(document.uri, "w")!!.use { it.write(PAYLOAD) }
                    requireNotNull(ManagedDownloadStoredEntryMapper.fromDocumentFile(document))
                }
            }
        }

        fun createAudio(name: String): ManagedDownloadStorage.StoredEntry = when (root) {
            is ManagedDownloadRootHandle.FileRoot -> ManagedDownloadStoredEntryMapper.fromFile(File(root.dir, name).apply { writeBytes(PAYLOAD) })
            is ManagedDownloadRootHandle.TreeRoot -> {
                val document = requireNotNull(root.tree.createFile("audio/mpeg", name))
                context.contentResolver.openOutputStream(document.uri, "w")!!.use { it.write(PAYLOAD) }
                requireNotNull(ManagedDownloadStoredEntryMapper.fromDocumentFile(document))
            }
        }

        fun warm(audio: ManagedDownloadStorage.StoredEntry) {
            val snapshot = ManagedDownloadStorage.composeSnapshot(listOf(audio), emptyList(), emptyMap(), emptyList(), emptyList())
            ManagedDownloadStorage.snapshotCacheStore.putSnapshot(context, ManagedDownloadStorage.snapshotCacheStore.currentKey(context), snapshot)
        }

        fun counters(): Bundle = requireNotNull(context.contentResolver.call(treeUri, ManagedDownloadMigrationTestDocumentProvider.QUERY_COUNT, null, null))
    }

    private companion object {
        const val AUTHORITY = ManagedDownloadMigrationTestDocumentProvider.AUTHORITY
        val PAYLOAD = byteArrayOf(1, 2, 3, 4)
    }
}
