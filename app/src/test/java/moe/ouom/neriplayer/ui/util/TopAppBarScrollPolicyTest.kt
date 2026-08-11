package moe.ouom.neriplayer.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopAppBarScrollPolicyTest {
    @Test
    fun shortContentLeavesBothEdgeDirectionsToOverscroll() {
        assertFalse(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = false,
                canScrollBackward = false,
                collapsedFraction = 0f
            )
        )
    }

    @Test
    fun collapsedBarCanRecoverWhenContentBecomesShort() {
        assertTrue(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = false,
                canScrollBackward = false,
                collapsedFraction = 0.5f
            )
        )
    }

    @Test
    fun genuinelyScrollableContentCanDriveTheTopAppBar() {
        assertTrue(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = true,
                canScrollBackward = false,
                collapsedFraction = 0f
            )
        )
        assertTrue(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = false,
                canScrollBackward = true,
                collapsedFraction = 0f
            )
        )
    }
}
