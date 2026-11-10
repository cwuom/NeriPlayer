package moe.ouom.neriplayer.ui.viewmodel.playlist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseCloudPagingPolicyTest {

    @Test
    fun `stops when the server reports no more pages`() {
        assertTrue(
            shouldStopNeteaseCloudPaging(
                hasMore = false,
                consumed = 200,
                parsedCount = 200,
                addedCount = 200
            )
        )
    }

    @Test
    fun `stops when the page consumed no items`() {
        assertTrue(
            shouldStopNeteaseCloudPaging(
                hasMore = true,
                consumed = 0,
                parsedCount = 0,
                addedCount = 0
            )
        )
    }

    @Test
    fun `stops when a non-empty page only repeats seen songs`() {
        assertTrue(
            shouldStopNeteaseCloudPaging(
                hasMore = true,
                consumed = 200,
                parsedCount = 200,
                addedCount = 0
            )
        )
    }

    @Test
    fun `continues when a page has no parsable songs but more pages exist`() {
        assertFalse(
            shouldStopNeteaseCloudPaging(
                hasMore = true,
                consumed = 200,
                parsedCount = 0,
                addedCount = 0
            )
        )
    }

    @Test
    fun `continues when a page adds new songs`() {
        assertFalse(
            shouldStopNeteaseCloudPaging(
                hasMore = true,
                consumed = 200,
                parsedCount = 200,
                addedCount = 1
            )
        )
    }
}
