package moe.ouom.neriplayer.data.history

import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
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
