package moe.ouom.neriplayer.util.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RetainedPlaybackCoverBitmapCacheTest {

    @Test
    fun `cache returns the exact frame and newest frame for an owner`() {
        val first = markerBitmap
        RetainedPlaybackCoverBitmapCache.put(
            ownerKey = "cache-test-song-a",
            coverUrl = "content://tree-a/cover.jpg",
            cacheKey = "generation-1",
            bitmap = first
        )

        assertEquals(
            first,
            RetainedPlaybackCoverBitmapCache.getExact(
                ownerKey = "cache-test-song-a",
                coverUrl = "content://tree-a/cover.jpg"
            )?.bitmap
        )
        assertEquals(
            "generation-1",
            RetainedPlaybackCoverBitmapCache.getLatestForOwner("cache-test-song-a")?.cacheKey
        )
    }

    @Test
    fun `cache does not return a frame for another owner or cover`() {
        RetainedPlaybackCoverBitmapCache.put(
            ownerKey = "cache-test-song-b",
            coverUrl = "content://tree-b/cover.jpg",
            cacheKey = null,
            bitmap = markerBitmap
        )

        assertNull(
            RetainedPlaybackCoverBitmapCache.getExact(
                ownerKey = "cache-test-song-a-missing",
                coverUrl = "content://tree-b/cover.jpg"
            )
        )
        assertNull(RetainedPlaybackCoverBitmapCache.getLatestForOwner("cache-test-song-a-missing"))
    }

    private companion object {
        val markerBitmap = object : ImageBitmap {
            override val width: Int = 1
            override val height: Int = 1
            override val colorSpace = ColorSpaces.Srgb
            override val hasAlpha: Boolean = true
            override val config: ImageBitmapConfig = ImageBitmapConfig.Argb8888

            override fun readPixels(
                buffer: IntArray,
                startX: Int,
                startY: Int,
                width: Int,
                height: Int,
                bufferOffset: Int,
                stride: Int
            ) = Unit

            override fun prepareToDraw() = Unit
        }
    }
}
