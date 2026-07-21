package moe.ouom.neriplayer.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteTogglePolicyTest {

    @Test
    fun `two taps reverse the optimistic favorite state`() {
        val firstTarget = nextFavoriteStateAfterTap(displayedIsFavorite = false)
        val secondTarget = nextFavoriteStateAfterTap(displayedIsFavorite = firstTarget)

        assertTrue(firstTarget)
        assertFalse(secondTarget)
    }
}
