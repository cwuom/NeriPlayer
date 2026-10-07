package moe.ouom.neriplayer.data.local.media

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.kyant.taglib.PropertyMap
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport.EditableCoverWritePlan
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

class LocalMediaEditableMetadataReadbackFailureTest {
    private val resolver = mock(ContentResolver::class.java)
    private val context = mock(Context::class.java).also { doReturn(resolver).`when`(it).contentResolver }
    private val song = SongItem(
        id = 7L,
        name = "Night Drive",
        artist = "Artist",
        album = "Album",
        albumId = 1L,
        durationMs = 180_000L,
        coverUrl = null
    )

    @Test
    fun `local files that cannot be reopened fail readback after one attempt`() {
        val source = uri("file", "file:///music/Night%20Drive.flac")

        val verified = LocalMediaSupport.verifyEditableMetadataReadback(
            context = context,
            song = song,
            sourceUri = source,
            resolved = resolved(source),
            metadataSnapshot = snapshotWithComments()
        )

        assertFalse(verified)
        verify(resolver, times(1)).openFileDescriptor(source, "r")
    }

    @Test
    fun `saf documents that cannot be reopened are retried before readback fails`() {
        val source = uri("content", "content://com.android.externalstorage.documents/document/night-drive")

        mockStatic(SystemClock::class.java).use { clock ->
            val verified = LocalMediaSupport.verifyEditableMetadataReadback(
                context = context,
                song = song,
                sourceUri = source,
                resolved = resolved(source),
                metadataSnapshot = snapshotWithComments()
            )

            assertFalse(verified)
            verify(resolver, times(SAF_WRITE_READBACK_RETRY_COUNT)).openFileDescriptor(source, "r")
            clock.verify { SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[1]) }
            clock.verify { SystemClock.sleep(SAF_WRITE_READBACK_DELAYS_MS[2]) }
        }
    }

    private fun snapshotWithComments() = LocalMediaSupport.EditableMetadataSnapshot(
        existingProperties = propertyMap("TITLE" to "Old Title"),
        updatedProperties = propertyMap(
            "TITLE" to "Night Drive",
            "COMMENT" to "kept",
            "comment:eng" to "kept too"
        ),
        picturePlan = EditableCoverWritePlan.Unchanged,
        expectedStandardLyrics = null,
        sourceStableKey = "local:night-drive",
        writesLyrics = false,
        clearsMissingLyrics = false,
        requiredEmbeddedPropertyKeys = setOf("TITLE")
    )

    private fun resolved(source: Uri) = LocalMediaSupport.ResolvedInspectableLocalMedia(
        queried = LocalMediaSupport.QueriedContentInfo(
            displayName = "Night Drive.flac",
            sizeBytes = null,
            mimeType = "audio/flac",
            lastModifiedMs = null,
            filePath = null,
            relativePath = null,
            title = null,
            artist = null,
            album = null,
            durationMs = null
        ),
        resolvedPath = null,
        file = null,
        playableUri = source,
        displayName = "Night Drive.flac",
        fallbackTitle = "Night Drive",
        fileExtension = "flac"
    )

    private fun propertyMap(vararg values: Pair<String, String>): PropertyMap =
        hashMapOf(*values.map { (key, value) -> key to arrayOf(value) }.toTypedArray())

    private fun uri(scheme: String, value: String): Uri {
        val uri = mock(Uri::class.java)
        doReturn(scheme).`when`(uri).scheme
        doReturn(value).`when`(uri).toString()
        return uri
    }
}
