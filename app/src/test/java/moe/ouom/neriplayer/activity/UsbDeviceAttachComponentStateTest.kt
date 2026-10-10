package moe.ouom.neriplayer.activity

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class UsbDeviceAttachComponentStateTest {

    private val packageManager = mock(PackageManager::class.java)
    private val context = mock(Context::class.java).also { context ->
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageManager).thenReturn(packageManager)
        `when`(context.packageName).thenReturn("moe.ouom.neriplayer")
    }

    @Test
    fun `an alias already in the desired state is left alone`() {
        currentAliasState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)

        assertFalse(UsbDeviceAttachHandling.applyComponentState(context, handlingEnabled = false))

        verify(packageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt())
    }

    @Test
    fun `disabling handling disables the alias without killing the app`() {
        currentAliasState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        assertTrue(UsbDeviceAttachHandling.applyComponentState(context, handlingEnabled = false))

        verify(packageManager).setComponentEnabledSetting(
            any(ComponentName::class.java),
            eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
            eq(PackageManager.DONT_KILL_APP)
        )
    }

    @Test
    fun `enabling handling restores the manifest default state`() {
        currentAliasState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)

        assertTrue(UsbDeviceAttachHandling.applyComponentState(context, handlingEnabled = true))

        verify(packageManager).setComponentEnabledSetting(
            any(ComponentName::class.java),
            eq(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
            eq(PackageManager.DONT_KILL_APP)
        )
    }

    @Test
    fun `package manager failures report that nothing changed`() {
        `when`(packageManager.getComponentEnabledSetting(any()))
            .thenThrow(IllegalArgumentException("Unknown component"))

        assertFalse(UsbDeviceAttachHandling.applyComponentState(context, handlingEnabled = true))

        verify(packageManager, never()).setComponentEnabledSetting(any(), anyInt(), anyInt())
    }

    private fun currentAliasState(state: Int) {
        `when`(packageManager.getComponentEnabledSetting(any())).thenReturn(state)
    }
}
