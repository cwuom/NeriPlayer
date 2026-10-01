package moe.ouom.neriplayer.platform.bilibili.api.sponsorblock

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliSponsorBlockClientTest {
    @Test
    fun `hash prefix matches the documented test video`() {
        assertEquals("5759", biliSponsorBlockHashPrefix("BV14741127BN"))
    }

}
