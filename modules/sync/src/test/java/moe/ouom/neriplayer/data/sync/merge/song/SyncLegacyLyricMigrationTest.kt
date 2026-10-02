package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaylist
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncLegacyLyricMigrationTest {
    @Test
    fun `unknown old matching cannot create an uploaded override or synthetic edit version`() {
        val unknown = SyncSong(id = 1, album = "netease", matchedLyric = "old automatic match",
            matchedTranslatedLyric = "translation", matchedRomanizedLyric = "romanized",
            matchedLyricSource = "CLOUD_MUSIC", matchedSongId = "42")
        val data = SyncSongLyricMergePolicy.converge(SyncData(playlists = listOf(SyncPlaylist(songs = listOf(unknown)))))
        val snapshot = data.playlists.single().songs.single()
        assertNull(snapshot.matchedLyric)
        assertNull(snapshot.matchedTranslatedLyric)
        assertNull(snapshot.matchedRomanizedLyric)
        assertFalse(snapshot.lyricSyncEdited == true)
        assertEquals(0L, snapshot.lyricSyncRevision)
        assertTrue(data.lyricOverrides.isEmpty())
    }
}
