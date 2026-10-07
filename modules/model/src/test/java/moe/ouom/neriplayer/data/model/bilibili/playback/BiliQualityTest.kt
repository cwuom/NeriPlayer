package moe.ouom.neriplayer.data.model.bilibili.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class BiliQualityTest {

    @Test
    fun `quality keys parse loosely and default to high`() {
        assertEquals(BiliQuality.HIRES, BiliQuality.fromKey(" HiRes "))
        assertEquals(BiliQuality.LOW, BiliQuality.fromKey("low"))
        assertEquals(BiliQuality.HIGH, BiliQuality.fromKey("ultra"))
    }

    @Test
    fun `degrade chains run from the requested quality down to low`() {
        assertEquals(
            listOf(BiliQuality.LOSSLESS, BiliQuality.HIGH, BiliQuality.MEDIUM, BiliQuality.LOW),
            BiliQuality.degradeChain(BiliQuality.LOSSLESS)
        )
        assertEquals(listOf(BiliQuality.LOW), BiliQuality.degradeChain(BiliQuality.LOW))
    }
}
