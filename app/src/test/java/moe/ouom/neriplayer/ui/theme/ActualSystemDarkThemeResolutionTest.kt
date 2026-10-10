package moe.ouom.neriplayer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class ActualSystemDarkThemeResolutionTest {

    private val uiModeManager = mock(UiModeManager::class.java)
    private val appConfiguration = Configuration()
    private val context = mock(Context::class.java).also { context ->
        val resources = mock(Resources::class.java).also { `when`(it.configuration).thenReturn(appConfiguration) }
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.resources).thenReturn(resources)
        `when`(context.getSystemService(UiModeManager::class.java)).thenReturn(uiModeManager)
        `when`(context.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(uiModeManager)
    }

    @Test
    fun `explicit night settings of the ui mode service win over the app configuration`() {
        nightMode(UiModeManager.MODE_NIGHT_YES)
        appUiMode(Configuration.UI_MODE_NIGHT_NO)
        assertTrue(isActualSystemDarkTheme(context))

        nightMode(UiModeManager.MODE_NIGHT_NO)
        appUiMode(Configuration.UI_MODE_NIGHT_YES)
        assertFalse(isActualSystemDarkTheme(context))
    }

    @Test
    fun `automatic and custom night schedules defer to the app configuration`() {
        nightMode(UiModeManager.MODE_NIGHT_AUTO)
        appUiMode(Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
        assertTrue(isActualSystemDarkTheme(context))

        nightMode(UiModeManager.MODE_NIGHT_CUSTOM)
        appUiMode(Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
        assertFalse(isActualSystemDarkTheme(context))
    }

    @Test
    fun `an undefined app night mode defers to the system configuration`() {
        `when`(context.getSystemService(UiModeManager::class.java)).thenReturn(null)
        `when`(context.getSystemService(Context.UI_MODE_SERVICE)).thenReturn(null)
        appUiMode(Configuration.UI_MODE_NIGHT_UNDEFINED)
        val systemConfiguration = Configuration()
        val systemResources = mock(Resources::class.java).also {
            `when`(it.configuration).thenReturn(systemConfiguration)
        }

        mockStatic(Resources::class.java).use { resources ->
            resources.`when`<Resources> { Resources.getSystem() }.thenReturn(systemResources)

            systemConfiguration.uiMode = Configuration.UI_MODE_NIGHT_YES
            assertTrue(isActualSystemDarkTheme(context))
            systemConfiguration.uiMode = Configuration.UI_MODE_NIGHT_NO
            assertFalse(isActualSystemDarkTheme(context))
        }
    }

    private fun nightMode(mode: Int) {
        `when`(uiModeManager.nightMode).thenReturn(mode)
    }

    private fun appUiMode(uiMode: Int) {
        appConfiguration.uiMode = uiMode
    }
}
