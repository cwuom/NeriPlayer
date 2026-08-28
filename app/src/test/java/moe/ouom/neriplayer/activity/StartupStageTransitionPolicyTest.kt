package moe.ouom.neriplayer.activity

import java.io.File
import moe.ouom.neriplayer.core.startup.StartupStage
import moe.ouom.neriplayer.core.startup.STARTUP_LOADING_INDICATOR_DELAY_MILLIS
import moe.ouom.neriplayer.core.startup.shouldKeepSystemSplash
import moe.ouom.neriplayer.core.startup.shouldShowStartupLoadingIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStageTransitionPolicyTest {
    @Test
    fun onboardingDefersOnlyDuringStartupBootstrap() {
        assertTrue(shouldDeferStartupStageContent(StartupStage.Onboarding))
        assertTrue(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Loading,
                disclaimerWasShown = true
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Disclaimer
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Onboarding,
                previousStage = StartupStage.Main
            )
        )
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Onboarding
            )
        )
        assertFalse(shouldDeferStartupStageContent(StartupStage.Loading))
        assertFalse(shouldDeferStartupStageContent(StartupStage.Disclaimer))
        assertFalse(
            shouldDeferStartupStageContent(
                stage = StartupStage.Main,
                previousStage = StartupStage.Loading
            )
        )
    }

    @Test
    fun startupStageMotionRemainsEnabledWithoutComposeLoadingPage() {
        val activitySource = source(
            "app/src/main/java/moe/ouom/neriplayer/activity/MainActivity.kt"
        )
        val transitionSource = source(
            "app/src/main/java/moe/ouom/neriplayer/activity/StartupStageTransition.kt"
        )

        assertTrue(activitySource.contains("AnimatedContent("))
        assertTrue(activitySource.contains("label = \"AppStageTransition\""))
        assertTrue(transitionSource.contains("AnimatedContent("))
        assertTrue(transitionSource.contains("label = \"startup_stage_content_gate\""))
        assertFalse(activitySource.contains("StartupLoadingContent("))
        assertTrue(activitySource.contains("setKeepOnScreenCondition"))
        assertTrue(shouldKeepSystemSplash(StartupStage.Loading))
        assertFalse(shouldKeepSystemSplash(StartupStage.Disclaimer))
        assertFalse(shouldKeepSystemSplash(StartupStage.Onboarding))
        assertFalse(shouldKeepSystemSplash(StartupStage.Main))
    }

    @Test
    fun startupSettingsReadTimeoutFailsOpenToAVisibleSafeStage() {
        assertNull(resolveStartupSetting(value = null, readTimedOut = false))
        assertEquals(false, resolveStartupSetting(value = null, readTimedOut = true))
        assertEquals(true, resolveStartupSetting(value = true, readTimedOut = true))
    }

    @Test
    fun startupLoadingIndicatorWaitsForOneSecond() {
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

    private fun source(path: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = File(directory, path)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("project source file not found: $path")
    }
}
