package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.model.SyncSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSongSourceMetadataTest {

    @Test
    fun `downloaded playback item retains remote fields for later sync`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42",
            playlistContextId = "playlist-123"
        )
        val downloadedSong = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Local Files",
            filePath = "/storage/emulated/0/Download/song.flac",
            fileSize = 1L,
            downloadTime = 1L,
            stableKey = remoteSong.stableKey(),
            sourceIdentityAlbum = remoteSong.identity().album,
            sourceChannelId = remoteSong.channelId,
            sourceAudioId = remoteSong.audioId,
            sourcePlaylistContextId = remoteSong.playlistContextId
        )

        val playbackSong = downloadedSong.toPlaybackSongItem()
        val syncSong = SyncSong.fromSongItemOrNull(playbackSong)

        assertTrue(LocalSongSupport.isLocalSong(playbackSong, null))
        assertEquals("netease", playbackSong.channelId)
        assertEquals("42", playbackSong.audioId)
        assertEquals("playlist-123", playbackSong.playlistContextId)
        assertTrue(playbackSong.sameIdentityAs(remoteSong))
        assertEquals(remoteSong.identity(), syncSong?.identity())
        assertEquals("playlist-123", syncSong?.playlistContextId)
    }

    @Test
    fun `downloaded bilibili playback item retains remote sync address`() {
        val remoteSong = SongItem(
            id = 66L,
            name = "song",
            artist = "artist",
            album = "Bilibili",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "123",
            subAudioId = "456"
        )
        val downloadedSong = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Local Files",
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L,
            stableKey = remoteSong.stableKey(),
            sourceIdentityAlbum = remoteSong.identity().album,
            sourceChannelId = remoteSong.channelId,
            sourceAudioId = remoteSong.audioId,
            sourceSubAudioId = remoteSong.subAudioId
        )

        val playbackSong = downloadedSong.toPlaybackSongItem()
        val syncSong = SyncSong.fromSongItemOrNull(playbackSong)

        assertTrue(LocalSongSupport.isLocalSong(playbackSong, null))
        assertEquals(remoteSong.identity(), playbackSong.identity())
        assertEquals(remoteSong.identity(), syncSong?.identity())
        assertEquals(remoteSong.stableKey(), playbackSong.sourceStableKey)
        assertEquals("bilibili", playbackSong.channelId)
        assertEquals("123", playbackSong.audioId)
        assertEquals("456", playbackSong.subAudioId)
        assertEquals("bilibili", syncSong?.channelId)
        assertEquals("123", syncSong?.audioId)
        assertEquals("456", syncSong?.subAudioId)
    }

    @Test
    fun `legacy downloaded bilibili album restores the remote resolution address`() {
        val downloadedSong = DownloadedSong(
            id = 123L,
            name = "song",
            artist = "artist",
            album = "Bilibili|456",
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L
        )

        val playbackSong = downloadedSong.toPlaybackSongItem()

        assertTrue(LocalSongSupport.isLocalSong(playbackSong, null))
        assertEquals("bilibili", playbackSong.channelId)
        assertEquals("123", playbackSong.audioId)
        assertEquals("456", playbackSong.subAudioId)
    }

    @Test
    fun `legacy downloaded source fields rebuild remote identity without a stable key`() {
        val remoteSong = SongItem(
            id = 66L,
            name = "song",
            artist = "artist",
            album = "Bilibili",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = "123",
            subAudioId = "456"
        )
        val downloadedSong = DownloadedSong(
            id = remoteSong.id,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Local Files",
            filePath = "/storage/emulated/0/Download/song.m4a",
            fileSize = 1L,
            downloadTime = 1L,
            sourceIdentityAlbum = remoteSong.identity().album,
            sourceChannelId = remoteSong.channelId,
            sourceAudioId = remoteSong.audioId,
            sourceSubAudioId = remoteSong.subAudioId
        )

        val playbackSong = downloadedSong.toPlaybackSongItem()
        val syncSong = SyncSong.fromSongItemOrNull(playbackSong)

        assertEquals(remoteSong.identity(), playbackSong.identity())
        assertEquals(remoteSong.identity(), syncSong?.identity())
        assertEquals("bilibili", syncSong?.channelId)
        assertEquals("123", syncSong?.audioId)
        assertEquals("456", syncSong?.subAudioId)
    }

    @Test
    fun `downloaded Netease playback restores the remote id from its stable source`() {
        val remoteSong = SongItem(
            id = 42L,
            name = "song",
            artist = "artist",
            album = "NeteaseAlbum",
            albumId = 0L,
            durationMs = 1_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = "42"
        )
        val downloadedSong = DownloadedSong(
            id = 9_999L,
            name = remoteSong.name,
            artist = remoteSong.artist,
            album = "Local Files",
            filePath = "/storage/emulated/0/Download/song.flac",
            fileSize = 1L,
            downloadTime = 1L,
            stableKey = remoteSong.stableKey(),
            sourceIdentityAlbum = remoteSong.identity().album,
            sourceChannelId = remoteSong.channelId,
            sourceAudioId = remoteSong.audioId
        )

        val playbackSong = downloadedSong.toPlaybackSongItem()
        val syncSong = SyncSong.fromSongItemOrNull(playbackSong)

        assertEquals(remoteSong.id, playbackSong.id)
        assertEquals(remoteSong.id, syncSong?.id)
        assertEquals(remoteSong.identity(), syncSong?.identity())
    }
}
