package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LocalMediaRetrieverTextMetadataTest {
    private val context = mock(Context::class.java)
    private val uri = mock(Uri::class.java)

    @Test
    fun `retriever tags are trimmed and parsed into text metadata`() {
        val tags = mapOf(
            MediaMetadataRetriever.METADATA_KEY_TITLE to "  Night Drive ",
            MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artist",
            MediaMetadataRetriever.METADATA_KEY_ALBUM to "Album",
            MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "   ",
            MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Composer",
            MediaMetadataRetriever.METADATA_KEY_GENRE to "Synthwave",
            MediaMetadataRetriever.METADATA_KEY_YEAR to "Released 2021-05-01",
            MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "3/12",
            MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to " 2 ",
            MediaMetadataRetriever.METADATA_KEY_DURATION to "180000",
            MediaMetadataRetriever.METADATA_KEY_MIMETYPE to "audio/flac",
            MediaMetadataRetriever.METADATA_KEY_BITRATE to "320499"
        )

        val metadata = readWithTags(tags)

        assertEquals(
            LocalMediaSupport.RetrieverTextMetadata(
                title = "Night Drive",
                artist = "Artist",
                album = "Album",
                albumArtist = null,
                composer = "Composer",
                genre = "Synthwave",
                year = 2021,
                trackNumber = 3,
                discNumber = 2,
                durationMs = 180_000L,
                mimeType = "audio/flac",
                bitrateKbps = 320,
                sampleRateHz = null
            ),
            metadata
        )
    }

    @Test
    fun `missing and unparseable numeric tags stay unknown`() {
        val unknown = LocalMediaSupport.RetrieverTextMetadata()

        assertEquals(unknown, readWithTags(emptyMap()))
        assertEquals(
            unknown,
            readWithTags(
                mapOf(
                    MediaMetadataRetriever.METADATA_KEY_YEAR to "unknown",
                    MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "A/B",
                    MediaMetadataRetriever.METADATA_KEY_DURATION to "n/a",
                    MediaMetadataRetriever.METADATA_KEY_BITRATE to "fast"
                )
            )
        )
    }

    @Test
    fun `sources the retriever cannot open give empty metadata and release it`() {
        mockConstruction(MediaMetadataRetriever::class.java) { retriever, _ ->
            doThrow(IllegalArgumentException("unsupported source")).`when`(retriever)
                .setDataSource(any(Context::class.java), any(Uri::class.java))
        }.use { construction ->
            val metadata = LocalMediaSupport.readRetrieverTextMetadata(context, uri)

            assertEquals(LocalMediaSupport.RetrieverTextMetadata(), metadata)
            verify(construction.constructed().single()).release()
        }
    }

    private fun readWithTags(tags: Map<Int, String>): LocalMediaSupport.RetrieverTextMetadata {
        return mockConstruction(MediaMetadataRetriever::class.java) { retriever, _ ->
            `when`(retriever.extractMetadata(anyInt())).thenAnswer { tags[it.getArgument<Int>(0)] }
        }.use { construction ->
            val metadata = LocalMediaSupport.readRetrieverTextMetadata(context, uri)
            val retriever = construction.constructed().single()
            verify(retriever).setDataSource(context, uri)
            verify(retriever).release()
            metadata
        }
    }
}
