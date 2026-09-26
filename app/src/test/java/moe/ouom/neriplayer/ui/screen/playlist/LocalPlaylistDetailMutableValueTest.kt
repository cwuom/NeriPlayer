package moe.ouom.neriplayer.ui.screen.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlaylistDetailMutableValueTest {
    @Test
    fun `delegated value reads and writes the original state owner`() {
        var backingValue = "initial"
        var state by LocalPlaylistDetailMutableValue(
            read = { backingValue },
            write = { value -> backingValue = value }
        )

        assertEquals("initial", state)

        state = "updated by extracted content"
        assertEquals("updated by extracted content", backingValue)

        backingValue = "updated by route"
        assertEquals("updated by route", state)
    }
}
