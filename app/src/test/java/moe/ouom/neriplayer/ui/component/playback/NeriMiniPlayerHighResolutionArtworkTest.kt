package moe.ouom.neriplayer.ui.component.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import java.io.File
import moe.ouom.neriplayer.ui.animation.LocalSharedCoverFlight
import moe.ouom.neriplayer.ui.animation.SharedCoverFlightState
import moe.ouom.neriplayer.ui.playback.visual.resolveNowPlayingOverlayArtwork
import moe.ouom.neriplayer.util.media.RetainedPlaybackCoverBitmapCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "sw360dp-w400dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NeriMiniPlayerHighResolutionArtworkTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val flight = SharedCoverFlightState()
    private var mounted by mutableStateOf(true)
    private var expandCount = 0

    @Test
    fun phonePreparesFullResolutionArtworkBeforeExpansion() = withLocalCovers { covers ->
        render(mutableStateOf(covers.first()), tablet = false)

        assertFullResolutionArtwork(covers.first(), awaitArtwork(covers.first()))
    }

    @Test
    fun switchingSongsPreparesFullResolutionArtworkThroughTheHiddenRequestBeforeExpansion() = withLocalCovers { covers ->
        val current = mutableStateOf(covers.first())
        render(current, tablet = false)
        val previous = awaitArtwork(covers.first())
        composeRule.runOnIdle {
            assertSame(previous, flight.miniCoverBitmap)
            current.value = covers.last()
        }
        composeRule.onNodeWithText(covers.last().ownerKey).assertExists(
            "the second song metadata must recompose before waiting for its artwork"
        )

        val next = awaitArtwork(covers.last())
        assertNotSame("switching songs must replace the previous artwork", previous, next)
        assertFullResolutionArtwork(covers.last(), next)
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp")
    fun tabletPreparesFullResolutionArtworkBeforeExpansion() = withLocalCovers { covers ->
        render(mutableStateOf(covers.first()), tablet = true)

        assertFullResolutionArtwork(covers.first(), awaitArtwork(covers.first()))
    }

    private fun render(current: MutableState<CoverFixture>, tablet: Boolean) {
        composeRule.setContent {
            if (mounted) {
                val cover = current.value
                CompositionLocalProvider(LocalSharedCoverFlight provides flight) {
                    MaterialTheme {
                        NeriMiniPlayer(
                            title = cover.ownerKey,
                            artist = "Artwork fixture",
                            coverUrl = cover.url,
                            coverIdentityKey = cover.ownerKey,
                            isPlaying = false,
                            onPlayPause = {},
                            onPrevious = {},
                            onNext = {},
                            onExpand = { expandCount++ },
                            enableBlur = false,
                            offlineMode = true,
                            tabletControls = if (tablet) {
                                MiniPlayerTabletControls(
                                    trackKey = cover.ownerKey,
                                    positionMs = 0L,
                                    durationMs = 60_000L,
                                    seekEnabled = true,
                                    shuffleEnabled = false,
                                    repeatMode = Player.REPEAT_MODE_OFF,
                                    onSeek = {},
                                    onShuffle = {},
                                    onRepeat = {},
                                    onVolume = {},
                                    onListenTogether = {},
                                    onQueue = {}
                                )
                            } else null
                        )
                    }
                }
            }
        }
    }

    private fun awaitArtwork(cover: CoverFixture): ImageBitmap {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            // 解码完成后仍需处理布局回调，才能把新封面交给共享转场
            composeRule.waitForIdle()
            val bitmap = RetainedPlaybackCoverBitmapCache.getExact(cover.ownerKey, cover.url)?.bitmap
            bitmap != null && flight.miniCoverBitmap === bitmap &&
                resolveNowPlayingOverlayArtwork(cover.ownerKey, flight) === bitmap
        }
        return composeRule.runOnIdle {
            checkNotNull(RetainedPlaybackCoverBitmapCache.getExact(cover.ownerKey, cover.url)).bitmap
        }
    }

    private fun assertFullResolutionArtwork(cover: CoverFixture, bitmap: ImageBitmap) {
        composeRule.runOnIdle {
            assertEquals("artwork must be prepared without opening Now Playing", 0, expandCount)
            assertEquals("the retained cover must already match the full size source", 512, bitmap.width)
            assertEquals("the retained cover must already match the full size source", 512, bitmap.height)
            assertEquals("the retained frame must belong to the requested song", cover.color,
                bitmap.asAndroidBitmap().getPixel(256, 256))
            assertSame("the shared transition must receive the full resolution frame", bitmap, flight.miniCoverBitmap)
            assertSame("the overlay must reuse the current frame prepared by MiniPlayer", bitmap,
                resolveNowPlayingOverlayArtwork(cover.ownerKey, flight))
        }
    }

    private fun withLocalCovers(test: (List<CoverFixture>) -> Unit) {
        val directory = File(context.cacheDir, "mini-player-highres-${System.nanoTime()}")
        check(directory.mkdir())
        val savedImageLoader = Coil.imageLoader(context)
        val imageLoader = ImageLoader.Builder(context).memoryCache(null).diskCache(null).build()
        try {
            val covers = listOf(Color.RED, Color.GREEN).mapIndexed { index, color ->
                val file = File(directory, "cover-$index.png")
                val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(color)
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                CoverFixture("${directory.name}-$index", Uri.fromFile(file).toString(), color)
            }
            Coil.setImageLoader(imageLoader)
            test(covers)
        } finally {
            try {
                composeRule.runOnIdle { mounted = false }
                composeRule.waitForIdle()
            } finally {
                try {
                    Coil.setImageLoader(savedImageLoader)
                    imageLoader.shutdown()
                } finally {
                    check(directory.deleteRecursively())
                }
            }
        }
    }

    private data class CoverFixture(val ownerKey: String, val url: String, val color: Int)
}
