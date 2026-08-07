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
                canScrollBackward = false
            )
        )
    }

    @Test
    fun genuinelyScrollableContentCanDriveTheTopAppBar() {
        assertTrue(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = true,
                canScrollBackward = false
            )
        )
        assertTrue(
            shouldAllowCollapsingTopAppBar(
                canScrollForward = false,
                canScrollBackward = true
            )
        )
    }
}
