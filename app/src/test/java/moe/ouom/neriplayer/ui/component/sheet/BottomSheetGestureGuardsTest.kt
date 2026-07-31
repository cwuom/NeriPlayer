package moe.ouom.neriplayer.ui.component.sheet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomSheetGestureGuardsTest {
    @Test
    fun downwardMotionPassesToParentOnlyWhenAllowed() {
        assertTrue(
            shouldPassBottomSheetMotionToParent(
                availableY = 24f,
                allowDownwardToParent = true
            )
        )

        assertFalse(
            shouldPassBottomSheetMotionToParent(
                availableY = 24f,
                allowDownwardToParent = false
            )
        )
    }

    @Test
    fun upwardAndSettledMotionStaysInsideSheet() {
        assertFalse(
            shouldPassBottomSheetMotionToParent(
                availableY = -24f,
                allowDownwardToParent = true
            )
        )

        assertFalse(
            shouldPassBottomSheetMotionToParent(
                availableY = 0f,
                allowDownwardToParent = true
            )
        )
    }

    @Test
    fun listenTogetherSheetDefaultGuardKeepsDownwardMotionInsideSheet() {
        assertFalse(
            shouldPassBottomSheetMotionToParent(
                availableY = 24f,
                allowDownwardToParent = false
            )
        )
    }
}
