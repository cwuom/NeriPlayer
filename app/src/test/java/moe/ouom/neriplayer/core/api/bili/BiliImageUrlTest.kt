package moe.ouom.neriplayer.core.api.bili

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliImageUrlTest {
    @Test
    fun appendsThumbnailOperationForBiliImage() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/cover.jpg@192w_108h_1c.webp",
            buildBiliThumbnailUrl(
                imageUrl = "https://i0.hdslb.com/bfs/archive/cover.jpg",
                width = 192,
                height = 108
            )
        )
        assertEquals(
            "https://p1.biliimg.com/cover.png@128w_128h_1c.webp",
            buildBiliThumbnailUrl(
                imageUrl = "https://p1.biliimg.com/cover.png",
                width = 128,
                height = 128
            )
        )
    }

    @Test
    fun replacesExistingOperationAndKeepsQueryAndFragment() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/cover.jpg@320w_180h_1c.webp?foo=bar#preview",
            buildBiliThumbnailUrl(
                imageUrl = "https://i0.hdslb.com/bfs/archive/cover.jpg@640w_400h_1c.webp?foo=bar#preview",
                width = 320,
                height = 180
            )
        )
    }

    @Test
    fun normalizesProtocolRelativeBiliImage() {
        assertEquals(
            "https://i0.hdslb.com/bfs/archive/cover.jpg@128w_128h_1c.webp",
            buildBiliThumbnailUrl(
                imageUrl = "//i0.hdslb.com/bfs/archive/cover.jpg",
                width = 128,
                height = 128
            )
        )
    }

    @Test
    fun leavesNonBiliImageUntouched() {
        val imageUrl = "https://example.com/cover.jpg?size=large"

        assertEquals(
            imageUrl,
            buildBiliThumbnailUrl(imageUrl = imageUrl, width = 192, height = 108)
        )
    }
}
