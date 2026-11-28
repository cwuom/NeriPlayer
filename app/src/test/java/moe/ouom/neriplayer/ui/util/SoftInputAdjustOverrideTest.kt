package moe.ouom.neriplayer.ui.util

import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
import android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED
import android.view.WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION
import android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
import android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftInputAdjustOverrideTest {

    @Suppress("DEPRECATION")
    private val adjustResize = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    @Test
    fun `first holder switches the window to resize and keeps state bits`() {
        val override = SoftInputAdjustOverride()
        val mode = SOFT_INPUT_STATE_HIDDEN or SOFT_INPUT_ADJUST_PAN

        val applied = override.acquire(mode)

        assertEquals(SOFT_INPUT_STATE_HIDDEN or adjustResize, applied)
        assertTrue(override.active)
    }

    @Test
    fun `overlapping holders during a transition restore only after the last one leaves`() {
        val override = SoftInputAdjustOverride()
        val applied = override.acquire(SOFT_INPUT_ADJUST_PAN)!!

        assertNull(override.acquire(applied))
        assertNull(override.release(applied))
        assertEquals(SOFT_INPUT_ADJUST_PAN, override.release(applied))
        assertFalse(override.active)
    }

    @Test
    fun `unspecified adjust is restored as pan because the view root keeps the last adjust`() {
        // 回归 #748: 写回 unspecified 时窗口仍停在 resize, 离开歌单页后其他页面失去平移
        val override = SoftInputAdjustOverride()
        val applied = override.acquire(SOFT_INPUT_ADJUST_UNSPECIFIED)!!

        val restored = override.release(applied or SOFT_INPUT_IS_FORWARD_NAVIGATION)

        assertEquals(SOFT_INPUT_ADJUST_PAN or SOFT_INPUT_IS_FORWARD_NAVIGATION, restored)
    }

    @Test
    fun `explicit adjust modes are restored unchanged`() {
        val override = SoftInputAdjustOverride()
        val applied = override.acquire(SOFT_INPUT_ADJUST_NOTHING)!!

        val restored = override.release(applied)!!

        assertEquals(SOFT_INPUT_ADJUST_NOTHING, restored and SOFT_INPUT_MASK_ADJUST)
    }

    @Test
    fun `release without a holder leaves the window alone`() {
        assertNull(SoftInputAdjustOverride().release(SOFT_INPUT_ADJUST_PAN))
    }
}
