package moe.ouom.neriplayer.core.player.persistence

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.sync.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlaybackLyricLegacyRecoveryGuardTest {

    private val legacyMatch = SyncSong(
        id = 1,
        album = "netease",
        matchedLyricSource = "CLOUD_MUSIC",
        matchedSongId = "7"
    )
    private val projection = PlaybackLyricOverrideProjection(listOf(legacyMatch))

    @Test
    fun `songs that already carry lyric state ignore legacy match metadata`() {
        listOf(
            song().copy(lyricSyncRevision = 3),
            song().copy(lyricSyncEdited = true),
            song().copy(matchedLyric = "cached"),
            song().copy(matchedTranslatedLyric = "cached translation"),
            song().copy(matchedRomanizedLyric = "cached romanized")
        ).forEach { assertSame(it, projection.song(it)) }
    }

    @Test
    fun `legacy match metadata is recovered only for the song it belongs to`() {
        val other = song().copy(id = 2)

        assertSame(other, projection.song(other))
        assertEquals(
            song().copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "7"),
            projection.song(song())
        )
    }

    private fun song() = SongItem(1, "title", "artist", "netease", 0, 1000, "https://cover")
}
