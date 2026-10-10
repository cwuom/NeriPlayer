package moe.ouom.neriplayer.util.platform

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@Suppress("DEPRECATION")
class BackgroundAppOpsAllowanceTest {

    private val appOps = mock(AppOpsManager::class.java)
    private val context = mock(Context::class.java).also { context ->
        `when`(context.packageName).thenReturn(PACKAGE)
        `when`(context.applicationInfo).thenReturn(ApplicationInfo().apply { uid = UID })
        `when`(context.getSystemService(AppOpsManager::class.java)).thenReturn(appOps)
    }

    @Test
    fun `a missing app ops service does not block background work`() {
        `when`(context.getSystemService(AppOpsManager::class.java)).thenReturn(null)

        assertTrue(context.areBackgroundAppOpsAllowedCompat())
    }

    @Test
    fun `allowed and default modes permit background work`() {
        modes(runInBackground = AppOpsManager.MODE_ALLOWED, runAnyInBackground = AppOpsManager.MODE_DEFAULT)

        assertTrue(context.areBackgroundAppOpsAllowedCompat())
        verify(appOps).checkOpNoThrow(RUN_IN_BACKGROUND, UID, PACKAGE)
        verify(appOps).checkOpNoThrow(RUN_ANY_IN_BACKGROUND, UID, PACKAGE)
    }

    @Test
    fun `an ignored run in background op blocks without checking the second op`() {
        modes(runInBackground = AppOpsManager.MODE_IGNORED, runAnyInBackground = AppOpsManager.MODE_ALLOWED)

        assertFalse(context.areBackgroundAppOpsAllowedCompat())
        verify(appOps, never()).checkOpNoThrow(eq(RUN_ANY_IN_BACKGROUND), anyInt(), anyString())
    }

    @Test
    fun `an errored run any in background op blocks background work`() {
        modes(runInBackground = AppOpsManager.MODE_ALLOWED, runAnyInBackground = AppOpsManager.MODE_ERRORED)

        assertFalse(context.areBackgroundAppOpsAllowedCompat())
    }

    @Test
    fun `failing app op checks are treated as allowed`() {
        `when`(appOps.checkOpNoThrow(anyString(), anyInt(), anyString())).thenThrow(SecurityException("denied"))

        assertTrue(context.areBackgroundAppOpsAllowedCompat())
    }

    private fun modes(runInBackground: Int, runAnyInBackground: Int) {
        `when`(appOps.checkOpNoThrow(RUN_IN_BACKGROUND, UID, PACKAGE)).thenReturn(runInBackground)
        `when`(appOps.checkOpNoThrow(RUN_ANY_IN_BACKGROUND, UID, PACKAGE)).thenReturn(runAnyInBackground)
    }

    private companion object {
        const val PACKAGE = "moe.ouom.neriplayer"
        const val UID = 10_123
        const val RUN_IN_BACKGROUND = "android:run_in_background"
        const val RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"
    }
}
