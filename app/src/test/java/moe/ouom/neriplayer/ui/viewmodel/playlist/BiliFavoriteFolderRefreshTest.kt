package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliFavoriteFolderRefreshTest {
    private val cached = listOf(video(1L), video(2L), video(3L))
    private val partial = listOf(video(1L))

    @Test
    fun `a complete fetch replaces the cached favorites`() {
        val refresh = resolveBiliFavoriteFolderRefresh(
            fetchedVideos = partial,
            missingPages = 0,
            cachedVideos = cached
        )

        assertTrue(refresh.complete)
        assertEquals(partial, refresh.videos)
    }

    @Test
    fun `a fetch with missing pages keeps showing the previous cache`() {
        val refresh = resolveBiliFavoriteFolderRefresh(
            fetchedVideos = partial,
            missingPages = 2,
            cachedVideos = cached
        )

        assertFalse(refresh.complete)
        assertEquals(cached, refresh.videos)
    }

    @Test
    fun `a fetch with missing pages and no cache shows the partial result as incomplete`() {
        val refresh = resolveBiliFavoriteFolderRefresh(
            fetchedVideos = partial,
            missingPages = 1,
            cachedVideos = null
        )

        assertFalse(refresh.complete)
        assertEquals(partial, refresh.videos)
    }

    private fun video(id: Long) = BiliVideoItem(
        id = id,
        bvid = "BV$id",
        title = "video $id",
        uploader = "uploader",
        coverUrl = "",
        durationSec = 0
    )
}
