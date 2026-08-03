package moe.ouom.neriplayer.ui.screen.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsHostNavigationStateTest {
    @Test
    fun queuedSecondBackWaitsForDownloadManagerToSettle() {
        assertFalse(
            shouldAdvanceSettingsScreenTransition(
                targetState = SettingsScreenState.DownloadManager,
                currentState = SettingsScreenState.DownloadProgress,
                isRunning = false,
                requestedState = SettingsScreenState.Settings,
                renderedScreenStates = setOf(
                    SettingsScreenState.DownloadManager,
                    SettingsScreenState.DownloadProgress
                )
            )
        )

        assertTrue(
            shouldAdvanceSettingsScreenTransition(
                targetState = SettingsScreenState.DownloadManager,
                currentState = SettingsScreenState.DownloadManager,
                isRunning = false,
                requestedState = SettingsScreenState.Settings,
                renderedScreenStates = setOf(SettingsScreenState.DownloadManager)
            )
        )
        assertEquals(
            SettingsScreenState.Settings,
            SettingsScreenState.DownloadManager.nextTowards(SettingsScreenState.Settings)
        )
    }

    @Test
    fun nestedTargetsMoveThroughEachDrawerLevel() {
        assertEquals(
            SettingsScreenState.DownloadManager,
            SettingsScreenState.Settings.nextTowards(SettingsScreenState.DownloadProgress)
        )
        assertEquals(
            SettingsScreenState.DownloadManager,
            SettingsScreenState.DownloadProgress.nextTowards(SettingsScreenState.Settings)
        )
    }

    @Test
    fun queuedBackWaitsUntilTheRetainedExitIsDisposed() {
        assertFalse(
            shouldAdvanceSettingsScreenTransition(
                targetState = SettingsScreenState.DownloadManager,
                currentState = SettingsScreenState.DownloadManager,
                isRunning = false,
                requestedState = SettingsScreenState.Settings,
                renderedScreenStates = setOf(
                    SettingsScreenState.DownloadManager,
                    SettingsScreenState.DownloadProgress
                )
            )
        )
    }
}
