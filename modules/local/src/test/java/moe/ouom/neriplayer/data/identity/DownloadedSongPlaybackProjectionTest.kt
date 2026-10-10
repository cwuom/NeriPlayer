package moe.ouom.neriplayer.data.identity

import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadedSongPlaybackProjectionTest {
    @Test
    fun `stored file names win over file references`() {
        assertEquals(
            "Stored.flac",
            downloaded(localFileName = "Stored.flac", mediaUri = "file:///music/Other.mp3").resolvedLocalFileName()
        )
    }

    @Test
    fun `file names fall back to the playable reference and then the file path`() {
        assertEquals(
            "From Uri.mp3",
            downloaded(mediaUri = "file:///music/From%20Uri.mp3").resolvedLocalFileName()
        )
        assertEquals(
            "fallback.ogg",
            downloaded(localFileName = " ", mediaUri = " ", filePath = "/music/fallback.ogg").resolvedLocalFileName()
        )
    }

    @Test
    fun `document references do not expose a file name`() {
        assertNull(downloaded(filePath = "content://downloads/public_downloads/5").resolvedLocalFileName())
    }

    @Test
    fun `remote identities come from the stored stable key`() {
        assertEquals(SongIdentity(12L, "netease", null), downloaded(stableKey = " 12|netease| ").remoteSourceIdentityOrNull())
    }

    @Test
    fun `blank or local stable keys without source fields have no remote identity`() {
        assertNull(downloaded(stableKey = null).remoteSourceIdentityOrNull())
        assertNull(downloaded(stableKey = "   ").remoteSourceIdentityOrNull())
        assertNull(downloaded(stableKey = "3|__local_files__|").remoteSourceIdentityOrNull())
    }

    @Test
    fun `playback items prefer the media uri and keep absolute file paths`() {
        val song = downloaded(
            filePath = "/music/Song.flac",
            mediaUri = "content://downloads/public_downloads/5",
            stableKey = "12|netease|"
        ).toPlaybackSongItem()

        assertEquals("content://downloads/public_downloads/5", song.mediaUri)
        assertEquals("/music/Song.flac", song.localFilePath)
        assertNull(song.localFileName)
        assertEquals(12L, song.id)
        assertEquals("netease", song.channelId)
        assertEquals("Album", song.album)
    }

    @Test
    fun `playback items for document downloads have no local file path`() {
        val song = downloaded(
            filePath = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FB.mp3",
            localFileName = "B.mp3"
        ).toPlaybackSongItem()

        assertEquals("content://com.android.externalstorage.documents/document/primary%3AMusic%2FB.mp3", song.mediaUri)
        assertNull(song.localFilePath)
        assertEquals("B.mp3", song.localFileName)
        assertEquals(5L, song.id)
        assertEquals(LocalSongSupport.LOCAL_ALBUM_IDENTITY, song.album)
    }

    @Test
    fun `blank media uris play the downloaded file`() {
        val song = downloaded(filePath = "/music/C.ogg", mediaUri = "  ").toPlaybackSongItem()

        assertEquals("/music/C.ogg", song.mediaUri)
        assertEquals("/music/C.ogg", song.localFilePath)
        assertEquals("C.ogg", song.localFileName)
    }

    private fun downloaded(
        filePath: String = "/music/Song.flac",
        mediaUri: String? = null,
        localFileName: String? = null,
        stableKey: String? = null
    ) = DownloadedSong(
        id = 5L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        filePath = filePath,
        fileSize = 1L,
        downloadTime = 1L,
        mediaUri = mediaUri,
        stableKey = stableKey,
        localFileName = localFileName
    )
}
