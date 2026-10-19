package moe.ouom.neriplayer.data.local.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class LocalMediaEmbeddedLyricsCancellationTest {
    private val context = mock(Context::class.java)
    private val source = mock(Uri::class.java).also {
        doReturn("content").`when`(it).scheme
        doReturn(SOURCE_REFERENCE).`when`(it).toString()
    }
    private val song = SongItem(
        id = 9L, name = "Song", artist = "Artist", album = "Album", albumId = 0L,
        durationMs = 1000L, coverUrl = null, mediaUri = SOURCE_REFERENCE
    )

    @Test
    fun `embedded lyrics propagate cancellation while resolving the local source`() {
        val cancellation = CancellationException("local source resolution cancelled")

        withSource {
            mockStatic(Class.forName(INSPECTION_CLASS)).use { inspection ->
                inspection.`when`<LocalMediaSupport.ResolvedInspectableLocalMedia> {
                    LocalMediaSupport.resolveInspectableLocalMedia(context, source, allowDescriptorFallback = true)
                }.thenThrow(cancellation)

                val failure = runCatching { LocalMediaSupport.inspectEmbeddedLyrics(context, song) }.exceptionOrNull()

                assertSame(cancellation, failure)
            }
        }
    }

    @Test
    fun `embedded lyrics propagate cancellation while inspecting tags`() {
        val cancellation = CancellationException("embedded tag inspection cancelled")
        val resolved = mock(LocalMediaSupport.ResolvedInspectableLocalMedia::class.java)
        doReturn(source).`when`(resolved).playableUri

        withSource {
            mockStatic(Class.forName(INSPECTION_CLASS)).use { inspection ->
                inspection.`when`<LocalMediaSupport.ResolvedInspectableLocalMedia> {
                    LocalMediaSupport.resolveInspectableLocalMedia(context, source, allowDescriptorFallback = true)
                }.thenReturn(resolved)
                inspection.`when`<LocalMediaSupport.TagLibMetadata> {
                    LocalMediaSupport.inspectTagLibMetadata(
                        context = context,
                        uri = source,
                        file = null,
                        includeEmbeddedAssets = false,
                        includeEmbeddedLyrics = true,
                        includeAudioProperties = false
                    )
                }.thenThrow(cancellation)

                val failure = runCatching { LocalMediaSupport.inspectEmbeddedLyrics(context, song) }.exceptionOrNull()

                assertSame(cancellation, failure)
            }
        }
    }

    private fun <T> withSource(block: () -> T): T = mockStatic(Uri::class.java).use { uris ->
        uris.`when`<Uri> { Uri.parse(SOURCE_REFERENCE) }.thenReturn(source)
        block()
    }

    private companion object {
        const val SOURCE_REFERENCE = "content://media/external/audio/media/9"
        const val INSPECTION_CLASS = "moe.ouom.neriplayer.data.local.media.LocalMediaSupportInspectionKt"
    }
}
