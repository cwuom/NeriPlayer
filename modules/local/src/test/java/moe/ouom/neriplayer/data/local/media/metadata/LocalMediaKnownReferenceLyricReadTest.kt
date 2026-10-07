package moe.ouom.neriplayer.data.local.media.metadata

import android.content.Context
import moe.ouom.neriplayer.data.local.media.DirectLocalLyricsInspection
import moe.ouom.neriplayer.data.local.media.LocalKnownSidecarReferences
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.NearbyLyricReferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.io.File

class LocalMediaKnownReferenceLyricReadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `known lyric files are read directly next to metadata overrides`() {
        val album = tempFolder.newFolder("album")
        val original = File(album, "Song.lrc").apply { writeText("[00:01.00]original") }
        val metadata = File(album, "Song.flac.npmeta.json").apply {
            writeText(
                """{"matchedLyric":"[00:01.00]metadata","originalTranslatedLyric":"[00:01.00]metadata translated"}"""
            )
        }
        val context = mock(Context::class.java)

        val inspection = LocalMediaSupport.inspectLyricsFromKnownReferences(
            context,
            LocalKnownSidecarReferences(
                lyrics = NearbyLyricReferences(
                    original = original.absolutePath,
                    translated = null,
                    romanized = "content://media/external/file/12"
                ),
                metadata = metadata.absolutePath
            )
        )

        assertEquals(
            DirectLocalLyricsInspection(
                original = "[00:01.00]original",
                translated = null,
                romanized = null,
                metadataOriginal = "[00:01.00]metadata",
                metadataTranslated = "[00:01.00]metadata translated",
                metadataRomanized = null,
                hasOriginalSidecar = true,
                hasTranslatedSidecar = false,
                hasRomanizedSidecar = false
            ),
            inspection
        )
        verifyNoInteractions(context)
    }

    @Test
    fun `media store references and missing files never count as sidecars`() {
        val album = tempFolder.newFolder("album")
        val romanized = File(album, "Song_roma.lrc").apply { writeText("[00:01.00]romanized") }
        val context = mock(Context::class.java)

        val inspection = LocalMediaSupport.inspectLyricsFromKnownReferences(
            context,
            LocalKnownSidecarReferences(
                lyrics = NearbyLyricReferences(
                    original = File(album, "Song.lrc").absolutePath,
                    translated = "content://com.android.providers.media.documents/document/audio%3A5",
                    romanized = romanized.absolutePath
                ),
                metadata = "content://media/external/file/13"
            )
        )

        assertEquals(
            DirectLocalLyricsInspection(
                original = null,
                translated = null,
                romanized = "[00:01.00]romanized",
                metadataOriginal = null,
                metadataTranslated = null,
                metadataRomanized = null,
                hasOriginalSidecar = false,
                hasTranslatedSidecar = false,
                hasRomanizedSidecar = true
            ),
            inspection
        )
        verifyNoInteractions(context)
    }
}
