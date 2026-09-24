package moe.ouom.neriplayer.core.download.execution

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.manager.batch.findStrictlyCompletedBatchSongKeys
import moe.ouom.neriplayer.core.download.model.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadBatchSnapshotPreflightTest {
    @Test
    fun thousandSongPreflightUsesIdentityIndexAndPreservesUnfinishedMembers() {
        val songs = List(1_000) { index -> SongItem(
            id = index + 1L, name = "snapshot-$index", artist = "fixture", album = "netease",
            albumId = 0L, durationMs = 1_000L, coverUrl = null
        ) }
        val entries = songs.map { song -> ManagedDownloadStorage.StoredEntry(
            name = "${song.id}.flac", reference = "/fixture/${song.id}.flac",
            mediaUri = "/fixture/${song.id}.flac", localFilePath = "/fixture/${song.id}.flac",
            sizeBytes = if (song.id == 523L) 0L else 1L, lastModifiedMs = 1L
        ) }
        val indexedEntries = songs.zip(entries).associate { (song, entry) ->
            song.stableKey() to CountingList(listOf(entry))
        }
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = entries, metadataEntries = emptyList(),
            metadataByAudioName = songs.zip(entries).associate { (song, entry) ->
                entry.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                    stableKey = song.stableKey(), downloadFinalized = song.id <= 524L,
                    metadataEmbeddingState = DownloadedAudioEmbeddingState.EMBEDDED_VERIFIED,
                    artifactState = if (song.id <= 524L) "FINALIZED" else "CORE_COMMITTED",
                    audioPublicationPending = song.id == 524L
                )
            }, coverEntries = emptyList(), lyricEntries = emptyList()
        ).copy(audioEntriesByStableKey = indexedEntries)
        val started = SystemClock.elapsedRealtimeNanos()
        val completed = GlobalDownloadManager.findStrictlyCompletedBatchSongKeys(songs, snapshot)
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        println("DOWNLOAD_SNAPSHOT_PREFLIGHT songs=1000 completed=${completed.size} elapsedMs=$elapsedMs")
        assertEquals(songs.take(522).map(SongItem::stableKey).toSet(), completed)
        assertEquals("完整快照预检不应为唯一身份重新排列文件名", 0,
            indexedEntries.values.sumOf { it.iterations })
        assertTrue("1000 首纯快照预检超出 5 秒: elapsedMs=$elapsedMs", elapsedMs < 5_000L)
        assertTrue(GlobalDownloadManager.findStrictlyCompletedBatchSongKeys(
            songs, snapshot.copy(rootEntriesComplete = false)
        ).isEmpty())
    }

    private class CountingList<T>(private val values: List<T>) : List<T> by values {
        var iterations = 0
            private set

        override fun iterator(): Iterator<T> {
            iterations++
            return values.iterator()
        }
    }
}
