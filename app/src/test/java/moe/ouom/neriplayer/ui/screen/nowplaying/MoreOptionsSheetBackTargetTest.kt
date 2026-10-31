package moe.ouom.neriplayer.ui.screen.nowplaying

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoreOptionsSheetBackTargetTest {

    private fun owner(initialPage: MoreOptionsPage = MoreOptionsPage.MAIN) = MoreOptionsSheetOwner(
        scope = CoroutineScope(Dispatchers.Unconfined),
        hide = {},
        expand = {},
        initialPage = initialPage,
        onDismiss = {}
    )

    @Test
    fun `main page leaves back to the sheet so it can animate its own dismissal`() {
        assertFalse(owner().canGoBackToMainPage)
    }

    @Test
    fun `sub pages go back to the main page`() {
        val owner = owner()
        owner.open(MoreOptionsPage.SEARCH)

        assertTrue(owner.canGoBackToMainPage)
        owner.back()
        assertEquals(MoreOptionsPage.MAIN, owner.page)
        assertFalse(owner.canGoBackToMainPage)
    }

    @Test
    fun `a sheet opened straight on a sub page dismisses instead of showing the main page`() {
        val owner = owner(initialPage = MoreOptionsPage.LYRIC_BEHAVIOR)

        assertFalse(owner.canGoBackToMainPage)
        owner.open(MoreOptionsPage.MAIN)
        owner.open(MoreOptionsPage.FONT_SIZE)
        assertTrue(owner.canGoBackToMainPage)
    }

    @Test
    fun `saving song info keeps back from leaving the edit page`() {
        val owner = owner()
        owner.open(MoreOptionsPage.EDIT_INFO)
        owner.setEditSaving(true)

        assertFalse(owner.canGoBackToMainPage)
        owner.back()
        assertEquals(MoreOptionsPage.EDIT_INFO, owner.page)
    }

    @Test
    fun `main page lets the sheet own the back gesture for its dismiss animation`() {
        assertTrue(owner().canPredictiveDismiss)
    }

    @Test
    fun `sub pages keep the back gesture with the animated page transition`() {
        val owner = owner()
        owner.open(MoreOptionsPage.ENCYCLOPEDIA)

        assertFalse(owner.canPredictiveDismiss)
    }

    @Test
    fun `a sheet opened straight on a sub page owns the back gesture because it dismisses`() {
        val owner = owner(initialPage = MoreOptionsPage.LYRIC_BEHAVIOR)

        assertTrue(owner.canPredictiveDismiss)
    }

    @Test
    fun `saving song info disables the predictive dismiss gesture`() {
        val owner = owner()
        owner.setEditSaving(true)

        assertFalse(owner.canPredictiveDismiss)
    }

    @Test
    fun `metadata application keeps the search visible until completion`() {
        val owner = owner()
        owner.open(MoreOptionsPage.SEARCH)
        owner.setMetadataApplying(true)

        owner.back()
        owner.dismiss()

        assertEquals(MoreOptionsPage.SEARCH, owner.page)
        assertFalse(owner.isDismissing)
        assertFalse(owner.canGoBackToMainPage)
        assertFalse(owner.canPredictiveDismiss)
        assertFalse(owner.sheetGesturesEnabled)

        owner.setMetadataApplying(false)
        assertTrue(owner.canGoBackToMainPage)
        assertTrue(owner.sheetGesturesEnabled)
    }

    @Test
    fun `an in flight dismissal owns back until the sheet is hidden`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val hidden = CompletableDeferred<Unit>()
        var dismissals = 0
        val owner = MoreOptionsSheetOwner(scope, hide = { hidden.await() }, expand = {}) { dismissals++ }
        try {
            assertTrue(owner.canPredictiveDismiss)
            owner.dismiss()
            assertTrue(owner.isDismissing)
            assertFalse(owner.canPredictiveDismiss)
            owner.dismiss()
            hidden.complete(Unit)
            assertEquals(1, dismissals)
            assertFalse(owner.isDismissing)
        } finally {
            scope.cancel()
        }
    }

}
