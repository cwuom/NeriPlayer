package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.storage.lookup.ManagedDownloadStorageLookup
import moe.ouom.neriplayer.core.download.storage.snapshot.ManagedDownloadSnapshotIndex
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedDownloadBatchLookupWorkTest {
    @Test
    fun `unique indexed identities avoid filename ranking for a thousand songs`() {
        val songs = List(1_000) { index ->
            SongItem(
                id = index + 1L, name = "song-$index", artist = "artist", album = "netease",
                albumId = 0L, durationMs = 1_000L, coverUrl = null
            )
        }
        val audio = songs.map { song ->
            ManagedDownloadStorage.StoredEntry(
                name = "${song.id}.flac", reference = "/music/${song.id}.flac",
                mediaUri = "/music/${song.id}.flac", localFilePath = "/music/${song.id}.flac",
                sizeBytes = 1L, lastModifiedMs = 1L
            )
        }
        val indexedEntries = songs.zip(audio).associate { (song, entry) ->
            song.stableKey() to CountingList(listOf(entry))
        }
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = audio, metadataEntries = emptyList(),
            metadataByAudioName = songs.zip(audio).associate { (song, entry) ->
                entry.name to ManagedDownloadStorage.DownloadedAudioMetadata(stableKey = song.stableKey())
            }, coverEntries = emptyList(), lyricEntries = emptyList()
        ).copy(audioEntriesByStableKey = indexedEntries)
        val started = System.nanoTime()
        val found = songs.map { song ->
            ManagedDownloadStorageLookup.findAudioEntry(snapshot, song, null)?.entry
        }
        println("DOWNLOAD_INDEXED_LOOKUP songs=1000 elapsedMs=${(System.nanoTime() - started) / 1_000_000L}")
        assertEquals(audio, found)
        assertEquals("唯一身份候选无需遍历并按文件名排名", 0,
            indexedEntries.values.sumOf { it.iterations })
    }

    @Test
    fun `empty audio list never expands filename candidates`() {
        val baseNames = CountingList(listOf("Song - Artist"))
        assertEquals(null, ManagedDownloadStorageLookup.findAudioEntry(emptyList(), baseNames))
        assertEquals("没有音频时无需构建候选文件名和正则", 0, baseNames.iterations)
    }

    @Test
    fun `single numeric id candidate still requires the remote identity to match`() {
        val song = SongItem(
            id = 42L, name = "Song", artist = "Artist", album = "netease",
            albumId = 0L, durationMs = 1_000L, coverUrl = null,
            channelId = "netease", audioId = "42"
        )
        val entry = ManagedDownloadStorage.StoredEntry(
            name = "foreign.flac", reference = "/music/foreign.flac",
            mediaUri = "/music/foreign.flac", localFilePath = "/music/foreign.flac",
            sizeBytes = 1L, lastModifiedMs = 1L
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(entry), metadataEntries = emptyList(),
            metadataByAudioName = mapOf(entry.name to ManagedDownloadStorage.DownloadedAudioMetadata(
                songId = 42L, stableKey = "foreign-source-42", channelId = "bilibili", audioId = "42"
            )), coverEntries = emptyList(), lyricEntries = emptyList()
        )
        assertEquals(null, ManagedDownloadStorageLookup.findAudioEntry(snapshot, song, null))
    }

    @Test
    fun `multiple identity matches still prefer the canonical filename`() {
        val song = SongItem(
            id = 42L, name = "Song", artist = "Artist", album = "netease",
            albumId = 0L, durationMs = 1_000L, coverUrl = null
        )
        val baseName = candidateManagedDownloadBaseNames(song).first()
        val canonical = ManagedDownloadStorage.StoredEntry(
            name = "$baseName.flac", reference = "/music/canonical.flac",
            mediaUri = "/music/canonical.flac", localFilePath = "/music/canonical.flac",
            sizeBytes = 1L, lastModifiedMs = 1L
        )
        val numbered = canonical.copy(
            name = "$baseName (2).flac", reference = "/music/numbered.flac",
            mediaUri = "/music/numbered.flac", localFilePath = "/music/numbered.flac", sizeBytes = 2L
        )
        val snapshot = ManagedDownloadSnapshotIndex.compose(
            audioEntries = listOf(numbered, canonical), metadataEntries = emptyList(),
            metadataByAudioName = listOf(numbered, canonical).associate { entry ->
                entry.name to ManagedDownloadStorage.DownloadedAudioMetadata(stableKey = song.stableKey())
            }, coverEntries = emptyList(), lyricEntries = emptyList()
        )
        assertEquals(canonical, ManagedDownloadStorageLookup.findAudioEntry(snapshot, song, null)?.entry)
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
