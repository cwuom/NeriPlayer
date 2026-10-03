package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLegacyLyricMigrationTest {
    @Test
    fun `unknown old matching is preserved in a compatibility override at the lowest positive version`() {
        val unknown = SyncSong(id = 1, album = "netease", matchedLyric = "old automatic match",
            matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            matchedLyricSource = "CLOUD_MUSIC", matchedSongId = "42")
        val data = SyncSongLyricMergePolicy.converge(SyncData(playlists = listOf(SyncPlaylist(songs = listOf(unknown)))))
        val snapshot = data.playlists.single().songs.single()
        assertEquals("old automatic match", snapshot.matchedLyric)
        assertEquals("translation", snapshot.matchedTranslatedLyric)
        assertEquals("romanized", snapshot.matchedRomanizedLyric)
        assertTrue(snapshot.lyricSyncEdited == true)
        assertEquals(1L, snapshot.lyricSyncRevision)
        assertEquals(snapshot, data.lyricOverrides.single())
    }
}
