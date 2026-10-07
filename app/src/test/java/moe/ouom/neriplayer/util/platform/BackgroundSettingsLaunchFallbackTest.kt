package moe.ouom.neriplayer.util.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic

class BackgroundSettingsLaunchFallbackTest {

    private val context = mock(Context::class.java).also { `when`(it.packageName).thenReturn("moe.ouom.neriplayer") }
    private val launchedActions = mutableListOf<String>()
    private val failures = mutableMapOf<String, RuntimeException>()

    @Test
    fun `app details settings open directly when available`() = withSettingsIntents {
        assertTrue(context.openAppBackgroundSettings())

        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), launchedActions)
    }

    @Test
    fun `missing app details settings fall back to the global settings screen`() = withSettingsIntents {
        failures[Settings.ACTION_APPLICATION_DETAILS_SETTINGS] = ActivityNotFoundException()

        assertTrue(context.openAppBackgroundSettings())

        assertEquals(
            listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS),
            launchedActions
        )
    }

    @Test
    fun `settings screens that are missing everywhere report failure`() = withSettingsIntents {
        failures[Settings.ACTION_APPLICATION_DETAILS_SETTINGS] = ActivityNotFoundException()
        failures[Settings.ACTION_SETTINGS] = ActivityNotFoundException()

        assertFalse(context.openAppBackgroundSettings())
    }

    @Test
    fun `settings screens blocked by security policy report failure`() = withSettingsIntents {
        failures[Settings.ACTION_APPLICATION_DETAILS_SETTINGS] = SecurityException("blocked")
        failures[Settings.ACTION_SETTINGS] = SecurityException("blocked")

        assertFalse(context.openAppBackgroundSettings())

        assertEquals(
            listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Settings.ACTION_SETTINGS),
            launchedActions
        )
    }

    @Test
    fun `a blocked battery optimization request falls back to app details settings`() = withSettingsIntents {
        failures[Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS] = SecurityException("blocked")

        assertTrue(context.requestIgnoreBatteryOptimizationsCompat())

        assertEquals(
            listOf(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ),
            launchedActions
        )
    }

    private fun withSettingsIntents(block: () -> Unit) {
        doAnswer { invocation ->
            val action = checkNotNull(invocation.getArgument<Intent>(0).action)
            launchedActions += action
            failures[action]?.let { throw it }
            null
        }.`when`(context).startActivity(any())
        mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(anyString()) }.thenReturn(mock(Uri::class.java))
            mockConstruction(Intent::class.java) { intent, construction ->
                `when`(intent.addFlags(anyInt())).thenReturn(intent)
                `when`(intent.action).thenReturn(construction.arguments().first() as String)
            }.use { block() }
        }
    }
}
