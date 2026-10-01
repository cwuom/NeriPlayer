package moe.ouom.neriplayer.util.units

import org.junit.Assert.assertEquals
import org.junit.Test

class MeasureUnitsTest {
    @Test
    fun `time expressions compose as milliseconds`() {
        val timeoutMs: Long = 1.hour + 2.minute + 3.second

        assertEquals(3_723_000L, timeoutMs)
        assertEquals(172_800_000L, 2.day)
    }

    @Test
    fun `conversion widens before multiplication to preserve large values`() {
        assertEquals(128_849_018_820_000L, Int.MAX_VALUE.minute)
        assertEquals(-7_730_941_132_800_000L, Int.MIN_VALUE.hour)
        assertEquals(185_542_587_100_800_000L, Int.MAX_VALUE.day)
    }

    @Test
    fun `zero and negative amounts remain signed time deltas`() {
        assertEquals(0L, 0.minute)
        assertEquals(-120_000L, (-2).minute)
        assertEquals(-3_000L, (-3).second)
    }
}
