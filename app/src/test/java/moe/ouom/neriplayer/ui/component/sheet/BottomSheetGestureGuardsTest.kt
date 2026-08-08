package moe.ouom.neriplayer.ui.component.sheet

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.ui.node.DelegatableNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassOverscrollFactory

class BottomSheetGestureGuardsTest {
    @Test
    fun customOverscrollReplacesNestedScrollGuard() {
        assertFalse(
            shouldInstallBottomSheetNestedScrollGuard(AdvancedGlassOverscrollFactory)
        )
        assertTrue(
            shouldInstallBottomSheetNestedScrollGuard(
                AdvancedGlassOverscrollFactory,
                preserveParentHandoff = true
            )
        )
        assertTrue(
            shouldInstallBottomSheetNestedScrollGuard(null)
        )
        assertTrue(
            shouldInstallBottomSheetNestedScrollGuard(object : OverscrollFactory {
                override fun createOverscrollEffect(): OverscrollEffect =
                    object : OverscrollEffect {
                        override val isInProgress: Boolean = false
                        override val node: DelegatableNode
                            get() = error("not needed")

                        override fun applyToScroll(
                            delta: androidx.compose.ui.geometry.Offset,
                            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                            performScroll: (androidx.compose.ui.geometry.Offset) -> androidx.compose.ui.geometry.Offset
                        ): androidx.compose.ui.geometry.Offset = performScroll(delta)

                        override suspend fun applyToFling(
                            velocity: androidx.compose.ui.unit.Velocity,
                            performFling: suspend (androidx.compose.ui.unit.Velocity) -> androidx.compose.ui.unit.Velocity
                        ) = Unit
                    }

                override fun equals(other: Any?): Boolean = this === other
                override fun hashCode(): Int = 2
            })
        )
    }

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
