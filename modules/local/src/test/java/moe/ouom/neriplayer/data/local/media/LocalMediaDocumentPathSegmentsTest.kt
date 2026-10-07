package moe.ouom.neriplayer.data.local.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mockStatic

class LocalMediaDocumentPathSegmentsTest {
    @Test
    fun `document ids are decoded and split into the path after their volume`() {
        mockStatic(Uri::class.java, CALLS_REAL_METHODS).use { uris ->
            uris.`when`<String> { Uri.decode("primary%3AMusic%2FAlbums%2F%2FLive") }.thenReturn("primary:Music/Albums//Live")
            uris.`when`<String> { Uri.decode("Downloads/Podcasts") }.thenReturn("Downloads/Podcasts")
            uris.`when`<String> { Uri.decode("") }.thenReturn("")

            assertEquals(
                listOf("Music", "Albums", "Live"),
                LocalMediaSupport.documentPathSegments("primary%3AMusic%2FAlbums%2F%2FLive")
            )
            assertEquals(listOf("Downloads", "Podcasts"), LocalMediaSupport.documentPathSegments("Downloads/Podcasts"))
            assertEquals(emptyList<String>(), LocalMediaSupport.documentPathSegments(null))
        }
    }

    @Test
    fun `segment lists match a prefix only segment by segment`() {
        with(LocalMediaSupport) {
            assertTrue(listOf("Music", "Albums", "Live").startsWithSegments(listOf("Music", "Albums")))
            assertTrue(listOf("Music").startsWithSegments(emptyList()))
            assertFalse(listOf("Music", "Albums").startsWithSegments(listOf("Music", "Albums", "Live")))
            assertFalse(listOf("Music", "Album").startsWithSegments(listOf("Music", "Albums")))
        }
    }
}
