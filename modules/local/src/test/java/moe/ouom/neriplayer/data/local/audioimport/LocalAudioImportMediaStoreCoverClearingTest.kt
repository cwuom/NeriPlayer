package moe.ouom.neriplayer.data.local.audioimport

import android.content.ContentResolver
import android.content.Context
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class LocalAudioImportMediaStoreCoverClearingTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun clearBefore() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @After
    fun clearAfter() {
        LocalMediaSupport.clearCoverLookupCache()
    }

    @Test
    fun `shared media store album art is cleared when no song specific cover exists`() {
        val album = tempFolder.newFolder("album")
        val audio = File(album, "Night Drive.flac").apply { writeBytes(ByteArray(8)) }
        val context = mock(Context::class.java)
        `when`(context.contentResolver).thenReturn(mock(ContentResolver::class.java))
        `when`(context.cacheDir).thenReturn(tempFolder.newFolder("cache"))
        `when`(context.filesDir).thenReturn(tempFolder.newFolder("files"))
        val song = SongItem(
            id = 41L,
            name = "Night Drive",
            artist = "Artist",
            album = "Album",
            albumId = 9L,
            durationMs = 180_000L,
            coverUrl = "content://media/external/audio/albumart/9",
            customCoverUrl = "   ",
            originalCoverUrl = " content://media/external/audio/albumart/9 ",
            localFilePath = audio.absolutePath
        )

        val result = LocalAudioImportManager.hydrateLocalSongCoverMetadataResult(context, song)

        assertEquals(song.copy(coverUrl = null, originalCoverUrl = null), result.song)
        assertEquals("   ", result.song.customCoverUrl)
        assertTrue(result.clearCoverUrl)
        assertTrue(result.clearOriginalCoverUrl)
    }
}
