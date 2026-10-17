package moe.ouom.neriplayer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverOwner
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.NowPlayingCoverRequestInput
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.StableNowPlayingCoverImage
import moe.ouom.neriplayer.ui.screen.nowplaying.cover.rememberNowPlayingCoverOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NowPlayingCoverReentryRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val firstSongKey = "cover-reentry-${System.nanoTime()}-first"
    private val firstCoverUrl = "content://cover-reentry/$firstSongKey"
    private var songKey by mutableStateOf(firstSongKey)
    private var coverUrl: String? by mutableStateOf(firstCoverUrl)
    private var coverVisible by mutableStateOf(false)
    private lateinit var owner: NowPlayingCoverOwner

    @Before
    fun setUp() {
        assumeComposeHostAvailable()
        composeRule.setContent {
            owner = rememberNowPlayingCoverOwner()
            MaterialTheme(colorScheme = lightColorScheme(primaryContainer = Color.Black)) {
                Box(Modifier.size(160.dp).background(Color.Black).testTag("cover")) {
                    if (coverVisible) {
                        StableNowPlayingCoverImage(
                            owner = owner,
                            coverUrl = coverUrl,
                            songKey = songKey,
                            context = LocalContext.current,
                            coverRequestSizePx = 160,
                            offlineMode = true,
                            coverCacheKey = "cover-reentry",
                            contentDescription = songKey,
                            modifier = Modifier.size(160.dp),
                            songKeyAliases = emptyList()
                        )
                    }
                }
            }
        }
        composeRule.runOnIdle {
            val request = checkNotNull(owner.newRequest(firstCoverUrl, firstSongKey, "cover-reentry"))
            owner.presentation(request, firstSongKey, emptyList())
            owner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = coverBitmap(Color.Red)))
            coverVisible = true
        }
        assertTrue("the first song must have a decoded cover before leaving", showsPreviousCover())
    }

    @Test
    fun returningFromLyricsAfterSwitchingSongsDoesNotReviveThePreviousCover() {
        composeRule.runOnIdle { coverVisible = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            songKey = "$firstSongKey-next"
            coverUrl = null
            coverVisible = true
        }

        assertFalse("a newly mounted cover must not paint the previous song while its own cover resolves", showsPreviousCover())

        val nextCoverUrl = "content://cover-reentry/$songKey"
        composeRule.runOnIdle { coverUrl = nextCoverUrl }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val request = checkNotNull(owner.requestFor(
                NowPlayingCoverRequestInput(nextCoverUrl, songKey, "cover-reentry")
            ))
            owner.publishDecodedFrame(request, request.frame.copy(decodedBitmap = coverBitmap(Color.Green)))
        }
        val current = centerColor()
        assertTrue("the current song must appear when its own bitmap arrives", current.green > 0.8f && current.red < 0.2f)
    }

    @Test
    fun switchingSongsOnTheVisibleCoverKeepsItsExistingHandoffFrame() {
        composeRule.runOnIdle {
            songKey = "$firstSongKey-next"
            coverUrl = null
        }

        assertTrue("continuous playback keeps its visible frame while the next cover resolves", showsPreviousCover())
    }

    @Test
    fun returningFromLyricsToTheSameSongKeepsItsDecodedCover() {
        composeRule.runOnIdle { coverVisible = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { coverVisible = true }

        assertTrue("reopening the same song must reuse its cached bitmap", showsPreviousCover())
    }

    private fun showsPreviousCover(): Boolean {
        val center = centerColor()
        return center.red > 0.8f && center.green < 0.2f && center.blue < 0.2f
    }

    private fun centerColor(): Color {
        composeRule.waitForIdle()
        val pixels = composeRule.onNodeWithTag("cover").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, pixels.height / 2]
    }

    private fun coverBitmap(color: Color): ImageBitmap = ImageBitmap(16, 16).also {
        Canvas(it).drawRect(Rect(0f, 0f, 16f, 16f), Paint().apply { this.color = color })
    }
}
