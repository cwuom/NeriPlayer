package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import java.io.File

class LocalMediaLyricsCacheKeyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `lyrics cache state lists every sidecar candidate with its size`() {
        val album = temporaryFolder.newFolder("album")
        val audio = File(album, "song.flac")
        val before = LocalMediaSupport.localLyricsCacheState(audio)
        val lyrics = File(album, "song.lrc").apply { writeText("[00:01]line") }

        val after = LocalMediaSupport.localLyricsCacheState(audio)

        assertNotEquals(before, after)
        assertTrue(after.startsWith("${File(album, "song.flac.npmeta.json").absolutePath}:0:0,"))
        assertTrue(after.contains("${lyrics.absolutePath}:11:${lyrics.lastModified()}"))
        assertTrue(after.contains("${File(album, "Lyrics/song_trans.lrc").absolutePath}:0:0"))
    }

    @Test
    fun `legacy downloads also watch the shared legacy lyrics folder`() {
        val state = LocalMediaSupport.localLyricsCacheState(File(LEGACY_DOWNLOAD_ROOT, "song.flac"))

        assertTrue(state.contains("$LEGACY_DOWNLOAD_ROOT/Lyrics/song.lrc:0:0"))
        assertEquals("", LocalMediaSupport.localLyricsCacheState(null))
        assertEquals("", LocalMediaSupport.localLyricsCacheState(File("song.flac")))
    }

    @Test
    fun `lyrics cache keys change with the source, fallbacks and file state`() {
        val album = temporaryFolder.newFolder("album")
        val audio = File(album, "song.flac").apply { writeText("audio") }
        val song = song(localFilePath = audio.absolutePath, localFileName = "song.flac")
        val source = mock(Uri::class.java)
        doReturn("content://media/external/audio/media/9").`when`(source).toString()

        val key = LocalMediaSupport.buildLocalLyricsCacheKey(song, source, true, false)

        assertEquals(
            parts(
                "local:9", audio.absolutePath, "song.flac", "content://media/external/audio/media/9", true, false,
                NO_LYRICS, "5:${audio.lastModified()}:${album.lastModified()}", LocalMediaSupport.localLyricsCacheState(audio)
            ),
            key
        )
        assertNotEquals(key, LocalMediaSupport.buildLocalLyricsCacheKey(song, source, true, true))
        audio.appendText(" with tags")
        assertNotEquals(key, LocalMediaSupport.buildLocalLyricsCacheKey(song, source, true, false))
    }

    @Test
    fun `detached songs key only on their identity and lyric model`() {
        val detached = song(localFilePath = null, localFileName = null).copy(matchedLyric = "[00:01]hi")
        val relative = song(localFilePath = "song.flac", localFileName = null)

        assertEquals(
            parts("local:9", null, null, null, false, false, parts("9:${"[00:01]hi".hashCode()}", "", "", "", "", ""), "", ""),
            LocalMediaSupport.buildLocalLyricsCacheKey(detached, null, false, false)
        )
        assertEquals(
            parts("local:9", "song.flac", null, null, false, false, NO_LYRICS, "0:0:null", ""),
            LocalMediaSupport.buildLocalLyricsCacheKey(relative, null, false, false)
        )
    }

    private fun parts(vararg values: Any?): String = values.joinToString("|")

    private fun song(localFilePath: String?, localFileName: String?) = SongItem(
        id = 9,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 0,
        durationMs = 0,
        coverUrl = null,
        localFileName = localFileName,
        localFilePath = localFilePath,
        sourceStableKey = "local:9"
    )

    private companion object {
        val NO_LYRICS = List(6) { "" }.joinToString("|")
    }
}
