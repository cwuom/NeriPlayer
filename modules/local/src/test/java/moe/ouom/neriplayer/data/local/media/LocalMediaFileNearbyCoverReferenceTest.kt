package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File

class LocalMediaFileNearbyCoverReferenceTest {
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
    fun `file sources with only unrelated images have no cover reference`() {
        val album = tempFolder.newFolder("album")
        val audio = File(album, "Song.flac").apply { writeBytes(ByteArray(8)) }
        File(album, "other.png").writeBytes(byteArrayOf(1, 2, 3))
        File(album, "notes.txt").writeText("liner notes")
        File(album, "Covers").mkdirs()
        val context = mock(Context::class.java)

        val reference = LocalMediaSupport.findNearbyCoverReference(
            context = context,
            uri = fileUri(audio),
            file = audio,
            displayName = audio.name
        )

        assertNull(reference)
        verifyNoInteractions(context)
    }

    @Test
    fun `file sources that no longer resolve to a file have no cover reference`() {
        val missing = File(tempFolder.root, "gone/Song.flac")
        val context = mock(Context::class.java)

        val reference = LocalMediaSupport.findNearbyCoverReference(
            context = context,
            uri = fileUri(missing),
            file = null,
            displayName = missing.name
        )

        assertNull(reference)
        verifyNoInteractions(context)
    }

    private fun fileUri(file: File): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn("file")
        `when`(uri.path).thenReturn(file.absolutePath)
        `when`(uri.toString()).thenReturn("file://${file.absolutePath}")
        return uri
    }
}
