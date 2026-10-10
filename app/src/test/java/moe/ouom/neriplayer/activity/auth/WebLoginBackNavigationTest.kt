package moe.ouom.neriplayer.activity.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLoginBackNavigationTest {

    private var history = 0
    private var goBackCalls = 0
    private val navigation = WebLoginBackNavigation(
        canGoBack = { history > 0 },
        goBack = {
            goBackCalls++
            history--
        }
    )

    @Test
    fun `back is left to the system until the page has history`() {
        assertFalse(navigation.callback.isEnabled)

        history = 2
        navigation.refresh()

        assertTrue(navigation.callback.isEnabled)
    }

    @Test
    fun `back walks the web history and then hands control back to the system`() {
        history = 2
        navigation.refresh()

        navigation.callback.handleOnBackPressed()
        assertTrue(navigation.callback.isEnabled)

        navigation.callback.handleOnBackPressed()

        assertEquals(2, goBackCalls)
        assertFalse(navigation.callback.isEnabled)
    }

    @Test
    fun `a new page after reaching the start intercepts back again`() {
        history = 1
        navigation.refresh()
        navigation.callback.handleOnBackPressed()
        assertFalse(navigation.callback.isEnabled)

        history = 1
        navigation.refresh()

        assertTrue(navigation.callback.isEnabled)
    }
}
