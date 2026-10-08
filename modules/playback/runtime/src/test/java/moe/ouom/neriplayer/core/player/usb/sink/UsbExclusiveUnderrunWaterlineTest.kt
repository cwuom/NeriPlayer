package moe.ouom.neriplayer.core.player.usb.sink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbExclusiveUnderrunWaterlineTest {
    @Test
    fun `steady playback keeps the lifecycle waterline`() {
        val waterline = UsbExclusiveUnderrunWaterline()

        assertEquals(125L, waterline.targetMs(125L, 0L, 0L))
        assertEquals(125L, waterline.targetMs(125L, 0L, 20L))
        assertNull(waterline.targetMs(null, 0L, 40L))
    }

    @Test
    fun `underruns while feeding double the waterline up to eight times`() {
        val waterline = UsbExclusiveUnderrunWaterline()
        waterline.targetMs(125L, 0L, 0L)

        assertEquals(250L, waterline.targetMs(125L, 4_800L, 20L))
        assertEquals(500L, waterline.targetMs(125L, 9_600L, 40L))
        assertEquals(1_000L, waterline.targetMs(125L, 14_400L, 60L))
        assertEquals(1_000L, waterline.targetMs(125L, 19_200L, 80L))
    }

    @Test
    fun `a stable period steps the boost back down one level at a time`() {
        val waterline = UsbExclusiveUnderrunWaterline(decayIntervalMs = 1_000L, continuityWindowMs = 2_000L)
        waterline.targetMs(125L, 0L, 0L)
        waterline.targetMs(125L, 4_800L, 10L)
        waterline.targetMs(125L, 9_600L, 20L)

        assertEquals(500L, waterline.targetMs(125L, 9_600L, 500L))
        assertEquals(250L, waterline.targetMs(125L, 9_600L, 1_020L))
        assertEquals(125L, waterline.targetMs(125L, 9_600L, 2_020L))
    }

    @Test
    fun `zero fill across a pause, a slow track load, or a counter reset does not boost`() {
        val waterline = UsbExclusiveUnderrunWaterline()
        waterline.targetMs(125L, 0L, 0L)

        assertEquals(125L, waterline.targetMs(125L, 48_000L, 5_000L))
        assertEquals(125L, waterline.targetMs(125L, 0L, 5_020L))
        assertEquals(125L, waterline.targetMs(125L, null, 5_040L))
        assertEquals(125L, waterline.targetMs(125L, 4_800L, 5_060L))
    }
}
