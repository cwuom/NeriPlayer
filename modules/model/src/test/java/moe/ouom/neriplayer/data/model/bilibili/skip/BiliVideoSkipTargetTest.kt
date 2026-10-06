package moe.ouom.neriplayer.data.model.bilibili.skip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliVideoSkipTargetTest {

    @Test
    fun `skip targets need a bvid and a positive cid`() {
        assertEquals(
            BiliVideoSkipTarget("BV1xx411c7mD", 42L),
            BiliVideoSkipTarget(" BV1xx411c7mD ", 42L).normalizedOrNull()
        )
        assertNull(BiliVideoSkipTarget("  ", 42L).normalizedOrNull())
        assertNull(BiliVideoSkipTarget("BV1xx411c7mD", 0L).normalizedOrNull())
        assertEquals("BV1xx411c7mD|42", BiliVideoSkipTarget("BV1xx411c7mD", 42L).stableKey())
    }
}
