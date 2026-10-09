package moe.ouom.neriplayer.ui.screen.nowplaying

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(owner().pageBackTarget)
    }

    @Test
    fun `sub pages go back to the main page`() {
        val owner = owner()
        owner.open(MoreOptionsPage.SEARCH)

        assertEquals(MoreOptionsPage.MAIN, owner.pageBackTarget)
        owner.back()
        assertEquals(MoreOptionsPage.MAIN, owner.page)
        assertNull(owner.pageBackTarget)
    }

    @Test
    fun `a sheet opened straight on a sub page dismisses instead of showing the main page`() {
        val owner = owner(initialPage = MoreOptionsPage.LYRIC_BEHAVIOR)

        assertNull(owner.pageBackTarget)
        owner.open(MoreOptionsPage.MAIN)
        owner.open(MoreOptionsPage.FONT_SIZE)
        assertEquals(MoreOptionsPage.MAIN, owner.pageBackTarget)
    }

    @Test
    fun `saving song info keeps back from leaving the edit page`() {
        val owner = owner()
        owner.open(MoreOptionsPage.EDIT_INFO)
        owner.setEditSaving(true)

        assertNull(owner.pageBackTarget)
        owner.back()
        assertEquals(MoreOptionsPage.EDIT_INFO, owner.page)
    }
}
