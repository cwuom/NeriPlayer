package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreCollapsingHeaderStateTest {
    @Test
    fun shrinkingHeaderClampsStoredOffset() {
        val state = collapsedHeader()

        state.heightPx = 120

        assertEquals(-120f, state.offsetPx, 0f)
    }

    @Test
    fun upwardDragAfterShrinkNeverConsumesInTheOppositeDirection() {
        val state = collapsedHeader()
        state.heightPx = 120

        val consumed = state.connection.onPreScroll(
            available = Offset(0f, -10f),
            source = NestedScrollSource.UserInput
        )

        assertEquals(Offset.Zero, consumed)
        assertEquals(-120f, state.offsetPx, 0f)
    }

    @Test
    fun downwardDragAfterShrinkConsumesOnlyTheAvailableDistance() {
        val state = collapsedHeader()
        state.heightPx = 120

        val consumed = state.connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, 10f),
            source = NestedScrollSource.UserInput
        )

        assertEquals(Offset(0f, 10f), consumed)
        assertEquals(-110f, state.offsetPx, 0f)
    }

    @Test
    fun growingHeaderKeepsTheExistingScrollPosition() {
        val state = collapsedHeader()

        state.heightPx = 260

        assertEquals(-200f, state.offsetPx, 0f)
    }

    private fun collapsedHeader(): ExploreCollapsingHeaderState =
        ExploreCollapsingHeaderState().apply {
            heightPx = 200
            connection.onPreScroll(Offset(0f, -200f), NestedScrollSource.UserInput)
        }
}
