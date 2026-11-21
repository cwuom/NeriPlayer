package moe.ouom.neriplayer.ui.screen.tab.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.request.CachePolicy
import coil.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeCleanComponentsTest {

    @Test
    fun separableBoxBlurPreservesSolidColor() {
        val color = Color.argb(255, 64, 128, 192)
        val pixels = IntArray(32 * 32) { color }

        applySeparableBoxBlur(pixels, width = 32, height = 32, radius = 4, passes = 3)

        assertTrue(pixels.all { it == color })
    }

    @Test
    fun separableBoxBlurSoftensSharpEdge() {
        val width = 32
        val height = 32
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) Color.BLACK else Color.WHITE
        }

        applySeparableBoxBlur(pixels, width = width, height = height, radius = 4, passes = 3)

        val seamRed = Color.red(pixels[(height / 2) * width + (width / 2)])
        assertTrue("Expected blurred seam pixel between 0 and 255, got $seamRed", seamRed in 40..215)
    }

    @Test
    fun separableBoxBlurIgnoresNonPositiveParameters() {
        val original = intArrayOf(Color.RED, Color.BLUE)
        val copy = original.copyOf()

        applySeparableBoxBlur(copy, width = 2, height = 1, radius = 0, passes = 3)
        applySeparableBoxBlur(copy, width = 2, height = 1, radius = 2, passes = 0)
        applySeparableBoxBlur(copy, width = 0, height = 1, radius = 2, passes = 1)

        assertTrue(original.contentEquals(copy))
    }

    @Test
    fun backdropTransformationDownscalesAndBlursLargeArtwork() = runBlocking {
        val input = createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                input.setPixel(x, y, if (y < 128) Color.BLACK else Color.WHITE)
            }
        }

        val output = HomeFeaturedBackdropBlurTransformation.transform(
            input = input,
            size = Size(HomeFeaturedBackdropMaxSizePx, HomeFeaturedBackdropMaxSizePx)
        )

        assertEquals(HomeFeaturedBackdropMaxSizePx, output.width)
        assertEquals(HomeFeaturedBackdropMaxSizePx, output.height)
        val midRed = Color.red(output.getPixel(output.width / 2, output.height / 2))
        assertTrue("Expected smoothed mid-row pixel, got $midRed", midRed in 40..215)
        input.recycle()
        output.recycle()
    }

    @Test
    fun backdropRequestUsesStaticBlurTransformationAndRespectsOfflineMode() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val url = "https://example.com/cover.jpg"

        val online = homeFeaturedLabelBackdropRequest(context, url, offlineMode = false)
        val offline = homeFeaturedLabelBackdropRequest(context, url, offlineMode = true)

        assertEquals(listOf(HomeFeaturedBackdropBlurTransformation), online.transformations)
        assertEquals(CachePolicy.ENABLED, online.memoryCachePolicy)
        assertEquals(CachePolicy.ENABLED, online.diskCachePolicy)
        assertEquals(CachePolicy.ENABLED, online.networkCachePolicy)
        assertEquals(CachePolicy.DISABLED, offline.networkCachePolicy)
        assertTrue(HomeFeaturedBackdropBlurTransformation.cacheKey.isNotBlank())
    }

    @Test
    fun remoteCoverFastPathSkipsDownloadedAndLocalCovers() {
        assertTrue(
            shouldUseHomeOnlineRemoteCoverFastPath(
                directCover = "https://example.com/cover.jpg",
                hasDownloadedCopy = false
            )
        )
        assertFalse(
            shouldUseHomeOnlineRemoteCoverFastPath(
                directCover = "https://example.com/cover.jpg",
                hasDownloadedCopy = true
            )
        )
        assertFalse(
            shouldUseHomeOnlineRemoteCoverFastPath(
                directCover = "content://media/external/audio/albumart/1",
                hasDownloadedCopy = false
            )
        )
        assertFalse(
            shouldUseHomeOnlineRemoteCoverFastPath(
                directCover = null,
                hasDownloadedCopy = false
            )
        )
    }
}
