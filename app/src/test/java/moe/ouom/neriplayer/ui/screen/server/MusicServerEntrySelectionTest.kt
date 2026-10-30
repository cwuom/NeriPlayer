package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import moe.ouom.neriplayer.platform.subsonic.repository.ServerAlbum
import moe.ouom.neriplayer.platform.subsonic.repository.ServerArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicServerEntrySelectionTest {
    @Test
    fun `summary savers preserve opaque ids profile and nullable covers`() {
        val artist = ServerArtist("profile / 中文", "artist: / 中文", "Artist", null, 7)
        val album = ServerAlbum("other-profile", "album: / 中文", "Album", "Artist", "opaque-cover", 12)

        assertEquals(artist, roundTrip(serverArtistSummarySaver, artist))
        assertEquals(album, roundTrip(serverAlbumSummarySaver, album))
        assertEquals(album.copy(coverUrl = null), roundTrip(serverAlbumSummarySaver, album.copy(coverUrl = null)))
        assertEquals(artist.copy(coverUrl = "opaque-cover"), roundTrip(serverArtistSummarySaver, artist.copy(coverUrl = "opaque-cover")))
        assertEquals(artist.copy(fallbackCoverUrl = "last-song-cover"),
            roundTrip(serverArtistSummarySaver, artist.copy(fallbackCoverUrl = "last-song-cover")))
        assertNull(roundTrip(serverArtistSummarySaver, null))
        assertNull(roundTrip(serverAlbumSummarySaver, null))
    }

    @Test
    fun `summary savers reject missing identities and keep safe optional defaults`() {
        assertNull(serverArtistSummarySaver.restore(listOf("profileId", "profile")))
        assertNull(serverAlbumSummarySaver.restore(listOf("profileId", "profile", "id", 1L)))
        assertNull(serverArtistSummarySaver.restore(listOf("profileId", " ", "id", "artist")))
        assertEquals(ServerArtist("profile", "artist", "", null, 0),
            serverArtistSummarySaver.restore(listOf("profileId", "profile", "id", "artist", "albumCount", -2)))
        assertEquals(ServerAlbum("profile", "album", "", "", null, 0),
            serverAlbumSummarySaver.restore(listOf("profileId", "profile", "id", "album")))
    }

    private fun <T> roundTrip(saver: Saver<T?, Any>, value: T?): T? {
        val scope = SaverScope { true }
        return with(saver) { scope.save(value) }?.let(saver::restore)
    }
}
