package moe.ouom.neriplayer.ui.util

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class CoverUrlStateComposeTest {
    @Test
    fun `restoring known empty local artwork clears the mounted display resolver immediately`() = runTest {
        val edited = SongItem(
            id = 74_123L,
            name = "Restore cover",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = "content://cover-reset-test/audio/74123",
            customCoverUrl = "file:///cover-reset-test/edited.jpg",
            originalCoverUrl = "",
            channelId = "local",
            audioId = "74123"
        )
        val fixture = CoverCompositionFixture(this)
        try {
            assertEquals(edited.customCoverUrl, fixture.compose(edited))

            val loading = edited.copy(customCoverUrl = null, originalCoverUrl = null)
            assertEquals(edited.customCoverUrl, fixture.compose(loading))

            val restored = loading.copy(originalCoverUrl = "")
            assertNull(fixture.compose(restored))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `restoring blank local artwork fields clears the mounted display resolver`() = runTest {
        val edited = SongItem(
            id = 74_124L,
            name = "Restore blank cover",
            artist = "Artist",
            album = "Album",
            albumId = 0L,
            durationMs = 60_000L,
            coverUrl = null,
            mediaUri = "content://cover-reset-test/audio/74124",
            customCoverUrl = "file:///cover-reset-test/edited-blank.jpg",
            originalCoverUrl = "",
            channelId = "local",
            audioId = "74124"
        )
        val blankArtworkFields = listOf(
            "" to null,
            null to "",
            "" to "",
            " \t " to null,
            null to " \t "
        )
        for ((coverUrl, customCoverUrl) in blankArtworkFields) {
            val fixture = CoverCompositionFixture(this)
            try {
                assertEquals(edited.customCoverUrl, fixture.compose(edited))

                val restored = edited.copy(coverUrl = coverUrl, customCoverUrl = customCoverUrl)
                assertNull("coverUrl=$coverUrl customCoverUrl=$customCoverUrl", fixture.compose(restored))
            } finally {
                fixture.close()
            }
        }
    }

    private class CoverCompositionFixture(scope: CoroutineScope) {
        private val recomposer = Recomposer(scope.coroutineContext)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val context = Mockito.mock(Context::class.java).also {
            Mockito.`when`(it.applicationContext).thenReturn(it)
        }

        fun compose(song: SongItem): String? {
            var result: String? = null
            composition.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    result = rememberSongDisplayCoverUrl(
                        song = song,
                        resolveLocalFallback = false,
                        downloadPresenceVersion = 0,
                        allowEmbeddedCoverFallback = false,
                        localAssetGeneration = "cover-reset-composition"
                    )
                }
            }
            return result
        }

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            recomposer.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
