package moe.ouom.neriplayer.data

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.media.normalizeLocalAlbumIdentity
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.platform.youtube.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.data.platform.youtube.stableYouTubeMusicId
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSongAlbumIdentityTest {

    @Test
    fun `local song identity uses stable local album key`() {
        val song = SongItem(
            id = 1L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/music/test.mp3"
        )

        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, song.identity().album)
    }

    @Test
    fun `local song identity normalizes file uri and path references`() {
        val pathSong = SongItem(
            id = 1L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/music/test.mp3"
        )
        val fileUriSong = pathSong.copy(
            mediaUri = "file:///music/test.mp3"
        )

        assertTrue(pathSong.sameIdentityAs(fileUriSong))
    }

    @Test
    fun `local song identity matches content uri and hydrated file path aliases`() {
        val contentSong = SongItem(
            id = 10L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "content://media/external/audio/media/42",
            channelId = "local",
            audioId = "10"
        )
        val hydratedSong = contentSong.copy(
            id = 20L,
            localFilePath = "/storage/emulated/0/Music/test.mp3",
            audioId = "20"
        )

        assertTrue(contentSong.sameIdentityAs(hydratedSong))
    }

    @Test
    fun `downloaded local song keeps source identity for library dedupe`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "Netease",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val downloadedLocalSong = SongItem(
            id = 99L,
            name = "song",
            artist = "artist",
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/storage/emulated/0/Music/song.mp3",
            localFilePath = "/storage/emulated/0/Music/song.mp3",
            channelId = "local",
            audioId = "99",
            sourceStableKey = remoteSong.stableKey()
        )

        assertTrue(downloadedLocalSong.sameIdentityAs(remoteSong))
        assertEquals(remoteSong.identity(), downloadedLocalSong.identity())
    }

    @Test
    fun `remote song identity preserves explicit album names`() {
        val song = SongItem(
            id = 2L,
            name = "test",
            artist = "artist",
            album = "Local Files",
            albumId = 12L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "https://example.com/test.mp3"
        )

        assertEquals("Local Files", song.identity().album)
    }

    @Test
    fun `normalizeLocalAlbumIdentity keeps explicit album names and only canonicalizes fallback`() {
        assertEquals(
            "Local Files",
            normalizeLocalAlbumIdentity("Local Files", usesFallbackAlbum = false)
        )
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("Local Files", usesFallbackAlbum = true)
        )
        assertEquals(
            LocalSongSupport.LOCAL_ALBUM_IDENTITY,
            normalizeLocalAlbumIdentity("", usesFallbackAlbum = false)
        )
    }

    @Test
    fun `netease identity ignores inconsistent album decoration`() {
        val fromHome = SongItem(
            id = 3L,
            name = "song",
            artist = "artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "3"
        )
        val fromPlaylist = fromHome.copy(
            album = "NeteaseAlbum",
            albumId = 9L
        )

        assertTrue(fromHome.sameIdentityAs(fromPlaylist))
        assertEquals(fromHome.identity(), fromPlaylist.identity())
    }

    @Test
    fun `legacy netease identity without channel still matches source tagged song`() {
        val legacyHomeSong = SongItem(
            id = 4L,
            name = "song",
            artist = "artist",
            album = "Album",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )
        val sourceTaggedSong = legacyHomeSong.copy(
            album = "NeteaseAlbum",
            albumId = 9L,
            channelId = "netease",
            audioId = "4"
        )

        assertTrue(legacyHomeSong.sameIdentityAs(sourceTaggedSong))
    }

    @Test
    fun `youtube music identity ignores playlist context`() {
        val videoId = "abc123"
        val fromHome = SongItem(
            id = stableYouTubeMusicId(videoId),
            name = "song",
            artist = "artist",
            album = "Home Shelf",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = buildYouTubeMusicMediaUri(videoId, playlistId = "playlist-a")
        )
        val fromPlaylist = fromHome.copy(
            album = "Playlist",
            albumId = 2L,
            mediaUri = buildYouTubeMusicMediaUri(videoId, playlistId = "playlist-b")
        )

        assertTrue(fromHome.sameIdentityAs(fromPlaylist))
    }

    @Test
    fun `youtube music identity falls back to channel audio id`() {
        val videoId = "channelOnlyVideo"
        val withMediaUri = SongItem(
            id = stableYouTubeMusicId(videoId),
            name = "song",
            artist = "artist",
            album = "Home Shelf",
            albumId = 1L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = buildYouTubeMusicMediaUri(videoId)
        )
        val withSourceFields = withMediaUri.copy(
            mediaUri = null,
            channelId = "youtubeMusic",
            audioId = videoId
        )

        assertTrue(withMediaUri.sameIdentityAs(withSourceFields))
    }

    @Test
    fun `bilibili identity matches legacy album hint and source fields`() {
        val legacySong = SongItem(
            id = 6L,
            name = "song",
            artist = "artist",
            album = "Bilibili",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null
        )
        val sourceTaggedSong = legacySong.copy(
            channelId = "bilibili",
            audioId = "6"
        )

        assertTrue(legacySong.sameIdentityAs(sourceTaggedSong))
    }

    @Test
    fun `local identity still separates different file paths`() {
        val first = SongItem(
            id = 5L,
            name = "song",
            artist = "artist",
            album = "Local Files",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            mediaUri = "/music/first.mp3"
        )
        val second = first.copy(mediaUri = "/music/second.mp3")

        assertFalse(first.sameIdentityAs(second))
    }
}
