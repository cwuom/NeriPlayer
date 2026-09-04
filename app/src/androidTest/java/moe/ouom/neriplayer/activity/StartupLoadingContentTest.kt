package moe.ouom.neriplayer.activity

import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.core.startup.StartupStage
import moe.ouom.neriplayer.core.startup.STARTUP_LOADING_INDICATOR_DELAY_MILLIS
import moe.ouom.neriplayer.core.startup.shouldKeepSystemSplash
import moe.ouom.neriplayer.core.startup.shouldShowStartupLoadingIndicator
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class StartupLoadingContentTest {

    @Test
    fun unresolvedStageKeepsSystemSplash() {
        assertTrue(shouldKeepSystemSplash(StartupStage.Loading))
    }

    @Test
    fun resolvedStagesReleaseSystemSplash() {
        assertFalse(shouldKeepSystemSplash(StartupStage.Disclaimer))
        assertFalse(shouldKeepSystemSplash(StartupStage.Onboarding))
        assertFalse(shouldKeepSystemSplash(StartupStage.Main))
    }

    @Test
    fun loadingIndicatorAppearsOnlyAfterTheStartupDelay() {
        assertFalse(shouldShowStartupLoadingIndicator(0L))
        assertFalse(
            shouldShowStartupLoadingIndicator(
                STARTUP_LOADING_INDICATOR_DELAY_MILLIS - 1L
            )
        )
        assertTrue(
            shouldShowStartupLoadingIndicator(
                STARTUP_LOADING_INDICATOR_DELAY_MILLIS
            )
        )
    }
}
