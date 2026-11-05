package moe.ouom.neriplayer.data.local.storage.host

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.PlaybackQueueSongEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.local.media.metadata.LocalMediaMetadataRecoveryStore
import moe.ouom.neriplayer.data.local.storage.cleanup.LocalImportCache
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalImportCoverReferencesTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun everyHistoricalCoverIsPreservedIncludingOrdinaryReimportedCovers() = runBlocking {
        val directory = temporary.newFolder()
        val oldCover = cover(directory, 'a', 7)
        val editedOriginal = cover(directory, 'b', 11)
        val distinctOriginal = cover(directory, 'c', 13)
        val currentCover = cover(directory, 'd', 17)
        val history = listOf(
            historicalSong(oldCover.path, oldCover.path),
            historicalSong(editedOriginal.path, editedOriginal.path, "https://example.com/custom.jpg"),
            historicalSong(oldCover.path, distinctOriginal.path)
        )
        val currentSong = SongItem(1, "track", "artist", "album", 0, 100, currentCover.path)
        val cache = LocalImportCache(directory, {
            mutableSetOf<String>().apply {
                history.forEach(::addHistoricalSong)
                addSong(currentSong)
            }
        }) {}

        assertEquals(ExtraCacheClearResult(true, 0, 0, 0), cache.clear())
        assertTrue(oldCover.exists())
        listOf(editedOriginal, distinctOriginal, currentCover).forEach { assertTrue(it.exists()) }
    }

    @Test
    fun songAndDownloadOriginalsRemainUsableAfterCleanup() = runBlocking {
        val directory = temporary.newFolder()
        val displayed = cover(directory, 'a', 7)
        val original = cover(directory, 'b', 11)
        val recovery = cover(directory, 'c', 13)
        val orphan = cover(directory, 'd', 17)
        val song = SongItem(1, "track", "artist", "album", 0, 100, displayed.toURI().toString(),
            originalCoverUrl = original.toURI().toString())
        val metadata = DownloadedAudioMetadata(restorableMetadata = ManagedDownloadRestorableMetadata(
            sourceStableKey = "local:1",
            baseline = ManagedDownloadRestorableMetadata.Baseline(coverReference = original.path),
            overrides = ManagedDownloadRestorableMetadata.Overrides(coverReference = displayed.path),
            legacyCoverRecoveryReferences = listOf(recovery.path)
        ))
        val cache = LocalImportCache(directory, {
            mutableSetOf<String>().apply { addSong(song); addMetadata(metadata) }
        }) {}

        assertEquals(ExtraCacheClearResult(true, 17, 0, 1), cache.clear())
        listOf(displayed, original, recovery).forEach { assertTrue(it.exists()) }
        assertFalse(orphan.exists())
    }

    @Test
    fun persistedShuffleQueueProtectsAnOriginalCoverOutsideTheCurrentQueue() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val directory = temporary.newFolder()
            val original = cover(directory, 'a', 7)
            val orphan = cover(directory, 'b', 11)
            database.playbackQueueDao().upsertSongs(listOf(queueSong(original.toURI().toString())))
            val cache = LocalImportCache(directory, {
                mutableSetOf<String>().also { readDatabaseCoverReferences(database, it) }
            }) {}

            assertEquals(ExtraCacheClearResult(true, 11, 0, 1), cache.clear())
            assertTrue(original.exists())
            assertFalse(orphan.exists())
        } finally {
            database.close()
        }
    }

    @Test
    fun recoverableDownloadOperationsBlockCleanupUntilTheyAreTerminal() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).allowMainThreadQueries().build()
        try {
            val directory = temporary.newFolder()
            val candidate = cover(directory, 'a', 7)
            val cache = LocalImportCache(directory, {
                mutableSetOf<String>().also { readDatabaseCoverReferences(database, it) }
            }) {}
            listOf("RUNNING", "DEGRADED_COMPLETE", "FINALIZED", "UNKNOWN_NEW_STATE").forEach { state ->
                database.downloadOperationDao().upsert(operation(state))
                assertEquals(ExtraCacheClearResult(false, 0, 0, 0), cache.clear())
                assertTrue(candidate.exists())
            }
            database.downloadOperationDao().upsert(operation("COMPLETED"))
            assertEquals(ExtraCacheClearResult(true, 7, 0, 1), cache.clear())
            assertFalse(candidate.exists())
        } finally {
            database.close()
        }
    }

    @Test
    fun nullableSnapshotFieldsDoNotHideNestedCoverReferences() {
        val file = temporary.newFile("last_playlist.json").apply {
            writeText("""{"playlist":[{"coverUrl":null,"originalCoverUrl":"file:/covers/original.jpg","matchedLyric":null}],"shuffleRestorePlaylist":null}""")
        }
        val references = mutableSetOf<String>()

        readLocalImportSnapshotReferences(file, references)

        assertEquals(setOf("file:/covers/original.jpg"), references)
    }

    @Test
    fun malformedSnapshotRootsAndCoverFieldsAbortReferenceCollection() {
        val file = temporary.newFile("last_playlist.json")
        listOf("null", "3", "\"not a queue\"", "{}", "[]", """{"playlist":[{"coverUrl":{}}]}""",
            """{"playlist":[null]}""", """{"playlist":[42]}""", """{"playlist":[],"shuffleRestorePlaylist":[null]}""").forEach { json ->
            file.writeText(json)
            assertThrows(IllegalStateException::class.java) { readLocalImportSnapshotReferences(file, mutableSetOf()) }
        }
    }

    @Test
    fun unresolvedMetadataJournalPreservesTheEntireCoverCache() = runBlocking {
        val directory = File(context.filesDir, "local_audio_covers").apply { mkdirs() }
        val candidate = cover(directory, 'e', 7)
        val journalDirectory = LocalMediaMetadataRecoveryStore.stagingDirectory(context).apply { mkdirs() }
        val journal = File(journalDirectory, "recovery-local-import-test.json").apply { writeText("broken") }
        try {
            assertEquals(ExtraCacheClearResult(false, 0, 0, 0), androidLocalImportCache(context) { emptyList() }.clear())
            assertTrue(candidate.exists())
        } finally {
            journal.delete()
            candidate.delete()
        }
    }

    private fun cover(directory: File, key: Char, bytes: Int) =
        File(directory, "${key.toString().repeat(64)}.jpg").apply { writeBytes(ByteArray(bytes)) }

    private fun historicalSong(cover: String, original: String, custom: String? = null) = PlayedEntry(
        id = 1, name = "track", artist = "artist", album = "album", durationMs = 100, coverUrl = cover,
        originalCoverUrl = original, customCoverUrl = custom, playedAt = 1
    )

    private fun queueSong(original: String) = PlaybackQueueSongEntity(
        queueId = "shuffle_restore", position = 0, id = 1, name = "track", artist = "artist", album = "album",
        albumId = 0, durationMs = 100, coverUrl = null, mediaUri = null, matchedLyric = null,
        matchedTranslatedLyric = null, matchedLyricSource = null, matchedSongId = null, userLyricOffsetMs = 0,
        customCoverUrl = null, customName = null, customArtist = null, originalName = null, originalArtist = null,
        originalCoverUrl = original, originalLyric = null, originalTranslatedLyric = null, localFileName = null,
        localFilePath = null, channelId = null, audioId = null, subAudioId = null, playlistContextId = null, streamUrl = null
    )

    private fun operation(state: String) = DownloadOperationEntity(
        operationId = "local-import-test", stableKey = "local:1", libraryId = "test", state = state, queueOrder = 0,
        sourceHintJson = "{}", stagingDirName = "stage", bytesWritten = 0, totalBytes = null, resumeJson = null,
        retryCount = 0, nextRetryAtMs = null, lastErrorCode = null, createdAtMs = 1, updatedAtMs = 1
    )
}
