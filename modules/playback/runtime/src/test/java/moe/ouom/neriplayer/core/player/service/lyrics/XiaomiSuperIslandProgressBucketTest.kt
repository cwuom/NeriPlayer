package moe.ouom.neriplayer.core.player.service.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XiaomiSuperIslandProgressBucketTest {

    @Test
    fun `interactive screen buckets progress every five percent`() {
        assertEquals(0, superIslandProgressBucket(4, screenInteractive = true))
        assertEquals(1, superIslandProgressBucket(5, screenInteractive = true))
        assertEquals(20, superIslandProgressBucket(100, screenInteractive = true))
    }

    @Test
    fun `screen off collapses progress so only line changes render`() {
        assertEquals(
            superIslandProgressBucket(10, screenInteractive = false),
            superIslandProgressBucket(85, screenInteractive = false)
        )
        assertEquals(SUPER_ISLAND_SCREEN_OFF_PROGRESS_BUCKET, superIslandProgressBucket(50, screenInteractive = false))
    }

    @Test
    fun `turning the screen back on changes the bucket and forces a fresh render`() {
        assertNotEquals(
            superIslandProgressBucket(0, screenInteractive = false),
            superIslandProgressBucket(0, screenInteractive = true)
        )
    }
}
