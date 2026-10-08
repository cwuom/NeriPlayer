package moe.ouom.neriplayer.data.local.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalSongSupportDuplicateKeysTest {
    @Test
    fun `local references audio ids and source keys become duplicate keys`() {
        val full = song(
            localFilePath = "/music/a.mp3", mediaUri = "FILE:///music/b%20c.mp3", channelId = "LOCAL",
            audioId = " 5 ", sourceStableKey = " key "
        )

        assertEquals(emptySet<String>(), LocalSongSupport.localDuplicateKeys(song(mediaUri = "https://cdn.example.com/a.mp3")))
        assertEquals(
            setOf("ref:/music/a.mp3", "ref:/music/b c.mp3", "audio:5", "source:key"),
            LocalSongSupport.localDuplicateKeys(full, includeMetadataFallback = true)
        )
        assertEquals(
            setOf("ref:Content://media/external/audio/media/9"),
            LocalSongSupport.localDuplicateKeys(song(mediaUri = "Content://media/external/audio/media/9", channelId = "netease", audioId = "5"))
        )
        assertEquals(
            setOf("ref:android.resource://pkg/raw/song"),
            LocalSongSupport.localDuplicateKeys(song(mediaUri = "android.resource://pkg/raw/song", channelId = "local", audioId = " "))
        )
    }

    @Test
    fun `unusual uri spellings fall back to android uri parsing`() {
        fun refs(mediaUri: String) = LocalSongSupport.localDuplicateKeys(song(localFilePath = "/music/x.mp3", mediaUri = mediaUri))

        assertEquals(setOf("ref:/music/x.mp3", "ref:/a b.mp3"), refs("file:///a b.mp3"))
        assertEquals(setOf("ref:/music/x.mp3", "ref:/single/slash.mp3"), refs("file:/single/slash.mp3"))
        assertEquals(setOf("ref:/music/x.mp3", "ref:content:/single"), refs("content:/single"))
        assertEquals(setOf("ref:/music/x.mp3"), refs("https://cdn.example.com/x.mp3"))
        assertEquals(setOf("ref:/music/x.mp3"), refs("file://host"))
        assertEquals(setOf("ref:/music/x.mp3"), refs("relative/x.mp3"))
    }

    @Test
    fun `metadata keys only identify songs that lost every local reference`() {
        val legacy = song(
            album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, localFileName = " Track.MP3 ",
            originalName = "Orig", originalArtist = " Who "
        )
        val relative = song(localFilePath = "relative.mp3", name = " ")
        val hostOnly = song(mediaUri = "file://host")

        assertEquals(emptySet<String>(), LocalSongSupport.localDuplicateKeys(legacy))
        assertEquals(setOf("file:track.mp3|1000", "meta:orig|who|1000"), LocalSongSupport.localDuplicateKeys(legacy, true))
        assertEquals(setOf("file:relative.mp3|1000"), LocalSongSupport.localDuplicateKeys(relative, true))
        assertEquals(setOf("meta:song|artist|1000"), LocalSongSupport.localDuplicateKeys(hostOnly, true))
        assertEquals(emptySet<String>(), LocalSongSupport.localDuplicateKeys(legacy.copy(durationMs = 0), true))
        assertEquals(emptySet<String>(), LocalSongSupport.localDuplicateKeys(hostOnly.copy(artist = " "), true))
    }

    @Test
    fun `songs share a local source when any duplicate key overlaps`() {
        val first = song(localFilePath = "/music/a.mp3", channelId = "local", audioId = "5")
        val moved = song(localFilePath = "/sdcard/a.mp3", channelId = "local", audioId = "5")
        val other = song(localFilePath = "/music/b.mp3", channelId = "local", audioId = "6")
        val legacy = song(album = LocalSongSupport.LOCAL_ALBUM_IDENTITY, localFileName = "a.mp3")
        val remote = song(mediaUri = "https://cdn.example.com/a.mp3")

        assertTrue(LocalSongSupport.hasSameLocalSource(first, moved))
        assertFalse(LocalSongSupport.hasSameLocalSource(first, other))
        assertFalse(LocalSongSupport.hasSameLocalSource(remote, remote))
        assertFalse(LocalSongSupport.hasSameLocalSource(legacy, legacy.copy(id = 2)))
        assertTrue(LocalSongSupport.hasSameLocalSource(legacy, legacy.copy(id = 2), includeMetadataFallback = true))
    }

    @Test
    fun `projected songs are local when addressed locally or filed under a local album`() {
        val names = setOf("Local Files")

        assertTrue(LocalSongSupport.isLocalSong("x", "content://media/1", null, names))
        assertTrue(LocalSongSupport.isLocalSong("local files", null, 0L, names))
        assertTrue(LocalSongSupport.isLocalSong(LocalSongSupport.LOCAL_ALBUM_IDENTITY, "", 0L, emptySet()))
        assertFalse(LocalSongSupport.isLocalSong("Other", null, 0L, names))
        assertFalse(LocalSongSupport.isLocalSong("Local Files", null, 1L, names))
        assertFalse(LocalSongSupport.isLocalSong("Local Files", null, null, names))
        assertFalse(LocalSongSupport.isLocalSong(" ", null, 0L, setOf(" ")))
        assertFalse(LocalSongSupport.isLocalSong("Local Files", "https://cdn.example.com/a.mp3", 0L, names))
    }

    private fun song(
        album: String = "Album",
        name: String = "Song",
        artist: String = "Artist",
        originalName: String? = null,
        originalArtist: String? = null,
        localFileName: String? = null,
        localFilePath: String? = null,
        mediaUri: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        sourceStableKey: String? = null
    ) = SongItem(
        id = 1, name = name, artist = artist, album = album, albumId = 0, durationMs = 1_000, coverUrl = null,
        mediaUri = mediaUri, originalName = originalName, originalArtist = originalArtist, localFileName = localFileName,
        localFilePath = localFilePath, channelId = channelId, audioId = audioId, sourceStableKey = sourceStableKey
    )
}
