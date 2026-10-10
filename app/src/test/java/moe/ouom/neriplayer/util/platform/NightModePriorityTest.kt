package moe.ouom.neriplayer.util.platform

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never

class NightModePriorityTest {

    @Test
    fun `forced dark wins over following the system`() {
        withDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) { delegate ->
            NightModeHelper.applyNightMode(followSystemDark = true, forceDark = true)

            delegate.verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        }
    }

    @Test
    fun `following the system applies when dark is not forced`() {
        withDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) { delegate ->
            NightModeHelper.applyNightMode(followSystemDark = true, forceDark = false)

            delegate.verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) }
        }
    }

    @Test
    fun `light mode is forced when neither option is enabled`() {
        withDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) { delegate ->
            NightModeHelper.applyNightMode(followSystemDark = false, forceDark = false)

            delegate.verify { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO) }
        }
    }

    @Test
    fun `an already active mode is not applied again`() {
        withDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) { delegate ->
            NightModeHelper.applyNightMode(followSystemDark = false, forceDark = true)

            delegate.verify({ AppCompatDelegate.setDefaultNightMode(anyInt()) }, never())
        }
    }

    private fun withDefaultNightMode(current: Int, block: (MockedStatic<AppCompatDelegate>) -> Unit) {
        mockStatic(AppCompatDelegate::class.java).use { delegate ->
            delegate.`when`<Int> { AppCompatDelegate.getDefaultNightMode() }.thenReturn(current)
            block(delegate)
        }
    }
}
