package moe.ouom.neriplayer.core.player.url

import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution as LocalPlaybackReferenceResolution
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SongUrlResolutionRetryTest {

    @Test
    fun `local playback retries only unstable resolutions`() {
        val song = SongItem(
            id = 1L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null
        )

        assertTrue(
            shouldRetryLocalPlaybackResolution(
                song,
                LocalPlaybackReferenceResolution.TemporarilyUnavailable(
                    "ProviderFailure(provider busy)"
                )
            )
        )
        assertFalse(
            shouldRetryLocalPlaybackResolution(
                song,
                LocalPlaybackReferenceResolution.Playable("content://provider/audio")
            )
        )
        assertFalse(
            shouldRetryLocalPlaybackResolution(
                song,
                LocalPlaybackReferenceResolution.NotIndexed
            )
        )
    }

    @Test
    fun `missing reference retries while the catalog commit or transfer can still settle`() {
        val song = SongItem(1L, "Song", "Artist", "Album", 1L, 1_000L, null)
        listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        ).forEach { (published, completed, active) ->
            val downloads = mock(PlayerDownloadAccess::class.java)
            `when`(downloads.hasDownloadedSongCached(song)).thenReturn(published)
            `when`(downloads.hasRecentlyCompletedAudio(song)).thenReturn(completed)
            `when`(downloads.isSongDownloadActive(song.stableKey())).thenReturn(active)
            val retry = shouldRetryLocalPlaybackResolution(song, LocalPlaybackReferenceResolution.Missing) { downloads }
            if (published || completed || active) assertTrue(retry) else assertFalse(retry)
        }
    }

    @Test
    fun `unindexed and playable references do not consult the download host`() {
        val song = SongItem(1L, "Song", "Artist", "Album", 1L, 1_000L, null)
        val unexpectedLookup: () -> PlayerDownloadAccess = { error("Unexpected download lookup") }
        assertFalse(shouldRetryLocalPlaybackResolution(song, LocalPlaybackReferenceResolution.NotIndexed, unexpectedLookup))
        assertFalse(shouldRetryLocalPlaybackResolution(song, LocalPlaybackReferenceResolution.Playable("content://audio"), unexpectedLookup))
        assertTrue(shouldRetryLocalPlaybackResolution(song, LocalPlaybackReferenceResolution.TemporarilyUnavailable("provider busy"), unexpectedLookup))
    }

}
