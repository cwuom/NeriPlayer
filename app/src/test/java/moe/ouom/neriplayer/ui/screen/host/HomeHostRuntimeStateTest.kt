package moe.ouom.neriplayer.ui.screen.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HomeHostRuntimeStateTest {

    @Test
    fun newRuntimeStateStartsHomeAtTop() {
        val state = HomeHostRuntimeState()

        assertEquals(0, state.gridState.firstVisibleItemIndex)
        assertEquals(0, state.gridState.firstVisibleItemScrollOffset)
        assertEquals(0f, state.topAppBarState.heightOffset, 0f)
        assertEquals(0f, state.topAppBarState.contentOffset, 0f)
        assertNull(state.pendingGridRestoreIndex)
        assertEquals(0, state.pendingGridRestoreOffset)
        assertNull(state.pendingGridRestoreKey)
        assertFalse(state.pendingGridRestoreArmed)
        assertEquals(emptyMap<String, Int>(), state.homeScrollAnchorIndexes)
    }

    @Test
    fun separateRuntimeStateDoesNotCarryPreviousRestoreRequest() {
        val previousState = HomeHostRuntimeState()
        previousState.pendingGridRestoreIndex = 8
        previousState.pendingGridRestoreOffset = 24
        previousState.pendingGridRestoreArmed = true

        val nextState = HomeHostRuntimeState()

        assertNull(nextState.pendingGridRestoreIndex)
        assertEquals(0, nextState.pendingGridRestoreOffset)
        assertFalse(nextState.pendingGridRestoreArmed)
    }
}
