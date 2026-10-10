package moe.ouom.neriplayer.data.local.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaRetrieverProbePolicyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `non MediaStore sources are always probed for retriever text`() {
        assertTrue(LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl(null, null))
        assertTrue(LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl("file:///music/song.flac", null))
    }

    @Test
    fun `MediaStore sources are probed only through a readable local file`() {
        val readable = temporaryFolder.newFile("song.flac").apply { writeText("audio") }
        val missing = File(temporaryFolder.root, "missing.flac")

        assertTrue(LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl(" CONTENT://MEDIA/external/audio/media/1 ", readable))
        assertFalse(LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl("content://media/external/audio/media/1", null))
        assertFalse(
            LocalMediaSupport.shouldProbeRetrieverTextMetadataImpl(
                "content://com.android.providers.media.documents/document/audio%3A1",
                missing
            )
        )
    }
}
