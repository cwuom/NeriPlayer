package moe.ouom.neriplayer.data.history

import com.google.gson.Gson
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.sync.mapping.toSongItem

import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayHistoryRepositoryPolicyTest {

    @Test
    fun `settled play history sync waits until playback has cooled down`() {
        assertTrue(
            playHistoryAutoSyncDelayMillis(PlayHistorySyncUrgency.SETTLED) >= 15_000L
        )
    }

    @Test
    fun `immediate play history sync keeps destructive mutations eager`() {
        assertEquals(
            0L,
            playHistoryAutoSyncDelayMillis(PlayHistorySyncUrgency.IMMEDIATE)
        )
    }

    @Test
    fun `played entry keeps the preferred granted uri beside its filesystem alias`() {
        val reference = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.flac"
        val source = SongItem(
            id = 42L, name = "Local title", artist = "Local artist", album = "Local album",
            albumId = 0L, durationMs = 1_000L, coverUrl = null,
            mediaUri = reference, localFilePath = "/storage/emulated/0/Music/song.flac",
            channelId = "netease", audioId = "42", sourceStableKey = "42|netease|",
            explicitLocalSourceReference = reference
        )

        val restored = source.toPlayedEntry(now = 1_000L).toSongItem()

        assertEquals(reference, restored.mediaUri)
        assertEquals(reference, restored.explicitLocalSourceReference)
        assertEquals(source.localFilePath, restored.localFilePath)
        assertEquals(source.stableKey(), restored.stableKey())
    }

    @Test
    fun `history JSON preserves a selected local source without requiring playlist membership`() {
        val reference = "content://media/external/audio/media/42"
        val source = SongItem(
            id = 42L, name = "Scanned title", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 1_000L, coverUrl = null, mediaUri = reference,
            channelId = "netease", audioId = "42", explicitLocalSourceReference = reference
        )
        val gson = Gson()
        val restored = gson.fromJson(gson.toJson(source.toPlayedEntry(1_000L)), PlayedEntry::class.java).toSongItem()

        assertEquals(reference, restored.explicitLocalSourceReference)
        assertEquals(reference, restored.mediaUri)
        assertEquals(source.stableKey(), restored.stableKey())
    }

    @Test
    fun `legacy history does not infer selected provenance from a local URI`() {
        val entry = Gson().fromJson("""{
            "id":42,"name":"Legacy","artist":"Artist","album":"Album","albumId":0,
            "durationMs":1000,"mediaUri":"content://media/external/audio/media/42","playedAt":1
        }""", PlayedEntry::class.java)

        assertNull(entry.explicitLocalSourceReference)
        assertNull(entry.toSongItem().explicitLocalSourceReference)
    }

    @Test
    fun `history cannot restore a source marker belonging to a different preferred URI`() {
        val source = SongItem(
            id = 42L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 1_000L, coverUrl = null, mediaUri = "content://media/external/audio/media/42",
            explicitLocalSourceReference = "content://media/external/audio/media/43"
        )

        assertNull(source.toPlayedEntry(1_000L).toSongItem().explicitLocalSourceReference)
        val persisted = source.toPlayedEntry(1_000L).copy(explicitLocalSourceReference = source.explicitLocalSourceReference)
        assertNull(persisted.toSongItem().explicitLocalSourceReference)
    }

    @Test
    fun `played entry round trip keeps local cover identity fields`() {
        val sourceSong = SongItem(
            id = 42L,
            name = "Local title",
            artist = "Local artist",
            album = "__local_files__",
            albumId = 0L,
            durationMs = 180_000L,
            coverUrl = "file:///covers/local.jpg",
            mediaUri = "content://media/external/audio/media/42",
            localFileName = "local.flac",
            localFilePath = "/storage/emulated/0/Music/local.flac",
            channelId = "local",
            audioId = "42",
            sourceStableKey = "123|netease|"
        )

        val restoredSong = sourceSong.toPlayedEntry(now = 1234L).toSongItem()

        assertEquals("local", restoredSong.channelId)
        assertEquals("42", restoredSong.audioId)
        assertEquals("123|netease|", restoredSong.sourceStableKey)
        assertEquals(sourceSong.stableKey(), restoredSong.stableKey())
    }
}
