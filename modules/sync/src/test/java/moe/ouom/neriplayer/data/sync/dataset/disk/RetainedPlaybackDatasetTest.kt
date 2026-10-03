package moe.ouom.neriplayer.data.sync.dataset.disk

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.sync.runtime.dataset.SyncPlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetainedPlaybackDatasetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun twoBorrowersAndOpenCursorKeepFilesUntilTheLastReferenceCloses() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val owner = source(store)
        val first = store.retainValidated(owner)
        val second = store.retainValidated(owner)
        val cursor = first.openTracks()
        try {
            owner.close()
            first.close()
            first.close()
            FileSyncPlaybackDatasetStore(directory)
            assertEquals("owned", read(second).single().identityKey)
            second.close()
            assertEquals(1, directory.listFiles().orEmpty().size)
            assertEquals("owned", cursor.nextPage().single().identityKey)
            assertTrue(cursor.nextPage().isEmpty())
        } finally {
            cursor.close()
            cursor.close()
            second.close()
            first.close()
            owner.close()
        }
        assertTrue(runCatching { cursor.nextPage() }.exceptionOrNull() is IllegalStateException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun sameLengthCorruptionRejectsRetentionWithoutReleasingExistingOwners() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val owner = source(store)
        val session = directory.listFiles().orEmpty().single()
        val tracks = File(session, "tracks-ordered.bin")
        val original = tracks.readBytes()
        try {
            RandomAccessFile(tracks, "rw").use { file ->
                file.seek(file.length() - 1)
                file.write(original.last().toInt() xor 1)
            }
            assertTrue(runCatching { store.retainValidated(owner) }.isFailure)
            FileSyncPlaybackDatasetStore(directory)
            assertTrue(session.isDirectory)
            tracks.writeBytes(original)
            store.retainValidated(owner).use { assertEquals("owned", read(it).single().identityKey) }
        } finally { owner.close() }
        assertFalse(session.exists())
    }

    @Test fun cancelledValidationReleasesItsBorrowAndLeavesTheOwnerUsable() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val owner = source(store)
        try {
            val failure = runCatching {
                store.retainValidated(owner) { throw CancellationException("cancel validation after retaining") }
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            store.retainValidated(owner).use { assertEquals("owned", read(it).single().identityKey) }
        } finally { owner.close() }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun recreatedStagingRootCanCreateAReplacementAfterCacheFilesDisappear() = runBlocking {
        val directory = temporary.newFolder()
        val store = FileSyncPlaybackDatasetStore(directory)
        val owner = source(store)
        assertTrue(directory.deleteRecursively())
        assertTrue(runCatching { store.retainValidated(owner) }.isFailure)
        owner.close()
        source(store).use { assertEquals("owned", read(it).single().identityKey) }
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private suspend fun source(store: FileSyncPlaybackDatasetStore): SyncPlaybackSource = store.newOrderedSink().use {
        it.appendTracks(listOf(SyncTrackStat(identityKey = "owned", name = "song")))
        it.seal()
    }

    private suspend fun read(source: SyncPlaybackSource): List<SyncTrackStat> = source.openTracks().use { cursor ->
        buildList {
            while (true) {
                val page = cursor.nextPage()
                if (page.isEmpty()) break
                addAll(page)
            }
        }
    }
}
