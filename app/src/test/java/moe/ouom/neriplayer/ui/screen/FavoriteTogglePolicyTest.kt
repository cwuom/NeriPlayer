package moe.ouom.neriplayer.ui.screen

import moe.ouom.neriplayer.ui.screen.playback.nextFavoriteStateAfterTap
import moe.ouom.neriplayer.ui.screen.playback.shouldConfirmFavoriteSync
import moe.ouom.neriplayer.data.identity.isSyncableRemoteSong
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTogglePolicyTest {

    @Test
    fun `two taps reverse the optimistic favorite state`() {
        val firstTarget = nextFavoriteStateAfterTap(displayedIsFavorite = false)
        val secondTarget = nextFavoriteStateAfterTap(displayedIsFavorite = firstTarget)

        assertTrue(firstTarget)
        assertFalse(secondTarget)
    }

    @Test
    fun `confirmed netease local favorite does not claim that its membership cannot sync`() {
        val matched = localSong().copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")

        assertFalse(shouldConfirmFavoriteSync(matched, adding = true))
        assertFalse("普通歌单仍按文件本身判断同步能力", matched.isSyncableRemoteSong())
    }

    @Test
    fun `unmatched and invalid local favorite additions retain the sync warning`() {
        val local = localSong()
        listOf(
            local,
            local.copy(matchedSongId = "42"),
            local.copy(matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "42"),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "0"),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "-1"),
            local.copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "invalid"),
            local.copy(channelId = "bilibili", audioId = "BV42", matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
        ).forEach { song -> assertTrue(song.toString(), shouldConfirmFavoriteSync(song, adding = true)) }
    }

    @Test
    fun `remote and downloaded source favorites do not show a local sync warning`() {
        val remote = localSong().copy(
            id = 42L, album = "netease", mediaUri = null, channelId = "netease", audioId = "42"
        )
        listOf(
            remote,
            localSong().copy(sourceStableKey = "42|netease|"),
            localSong().copy(sourceStableKey = "77|bilibili|"),
            localSong().copy(channelId = "netease", audioId = "42")
        ).forEach { song -> assertFalse(song.toString(), shouldConfirmFavoriteSync(song, adding = true)) }
    }

    @Test
    fun `favorite removals and missing current songs never request sync confirmation`() {
        val matched = localSong().copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
        assertFalse(shouldConfirmFavoriteSync(localSong(), adding = false))
        assertFalse(shouldConfirmFavoriteSync(matched, adding = false))
        assertFalse(shouldConfirmFavoriteSync(null, adding = false))
        assertFalse(shouldConfirmFavoriteSync(null, adding = true))
    }

    @Test
    fun `batch favorites require confirmation only when some member cannot sync`() {
        val matched = localSong().copy(matchedLyricSource = MusicPlatform.CLOUD_MUSIC, matchedSongId = "42")
        val downloaded = localSong().copy(sourceStableKey = "77|bilibili|")
        val unsupported = localSong().copy(matchedLyricSource = MusicPlatform.QQ_MUSIC, matchedSongId = "42")

        assertFalse(shouldConfirmFavoriteSync(emptyList()))
        assertFalse(shouldConfirmFavoriteSync(listOf(matched, downloaded)))
        assertTrue(shouldConfirmFavoriteSync(listOf(matched, unsupported)))
        assertTrue(shouldConfirmFavoriteSync(listOf(unsupported, matched)))
    }

    private fun localSong() = SongItem(
        id = 99L, name = "Local song", artist = "Artist", album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
        albumId = 0L, durationMs = 60_000L, coverUrl = null, mediaUri = "/music/local.mp3",
        channelId = "local", audioId = "99"
    )
}
