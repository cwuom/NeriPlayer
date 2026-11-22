package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupOnboardingFlowTest {

    @Test
    fun `language, style and motion come first and privacy precedes every data step`() {
        val steps = StartupStep.entries
        assertEquals(
            listOf(StartupStep.Language, StartupStep.Style, StartupStep.Motion, StartupStep.Privacy),
            steps.take(4)
        )
        assertEquals(StartupStep.LearningGuide, steps.last())
        steps.filter { it.ordinal <= StartupStep.Privacy.ordinal }.forEach { assertFalse(it.requiresConsent) }
        steps.filter { it.ordinal > StartupStep.Privacy.ordinal }.forEach { assertTrue(it.requiresConsent) }
    }

    @Test
    fun `steps behind the privacy notice stay locked until it is accepted`() {
        assertTrue(canEnterStartupStep(StartupStep.Motion, disclaimerAccepted = false))
        assertTrue(canEnterStartupStep(StartupStep.Privacy, disclaimerAccepted = false))
        assertFalse(canEnterStartupStep(StartupStep.Platforms, disclaimerAccepted = false))
        assertTrue(canEnterStartupStep(StartupStep.Platforms, disclaimerAccepted = true))
        assertFalse(canLeaveStartupStepForward(StartupStep.Privacy, disclaimerAccepted = false))
        assertTrue(canLeaveStartupStepForward(StartupStep.Privacy, disclaimerAccepted = true))
        assertTrue(canLeaveStartupStepForward(StartupStep.Style, disclaimerAccepted = false))
    }

    @Test
    fun `chapters group consecutive steps for the segmented progress`() {
        val segments = startupChapterSegments()
        assertEquals(StartupChapter.entries, segments.map(StartupChapterSegment::chapter))
        assertEquals(StartupStep.entries.size, segments.sumOf(StartupChapterSegment::stepCount))
        assertEquals(StartupChapterSegment(StartupChapter.Look, 0, 3), segments.first())
        assertEquals(StartupChapterSegment(StartupChapter.Privacy, 3, 1), segments[1])
    }

    @Test
    fun `only tablet landscape merges the lyrics step into now playing`() {
        assertTrue(isStartupTabletLandscape(isLandscape = true, smallestScreenWidthDp = 800))
        assertFalse(isStartupTabletLandscape(isLandscape = false, smallestScreenWidthDp = 800))
        // 手机横屏仍有单独的歌词页
        assertFalse(isStartupTabletLandscape(isLandscape = true, smallestScreenWidthDp = 411))

        assertEquals(StartupStep.entries, startupStepsFor(tabletLandscape = false))
        val landscape = startupStepsFor(tabletLandscape = true)
        assertEquals(StartupStep.entries - StartupStep.Lyrics, landscape)
        val playback = startupChapterSegments(landscape).single { it.chapter == StartupChapter.Playback }
        assertEquals(StartupChapterSegment(StartupChapter.Playback, 7, 1), playback)
    }

    @Test
    fun `portrait tablets read in one column like phones and only wide landscape splits panes`() {
        assertFalse(isStartupOnboardingTwoPane(width = 800.dp, height = 1_200.dp))
        assertFalse(isStartupOnboardingTwoPane(width = 600.dp, height = 900.dp))
        assertFalse(isStartupOnboardingTwoPane(width = 360.dp, height = 640.dp))
        assertFalse(isStartupOnboardingTwoPane(width = 568.dp, height = 320.dp))
        assertTrue(isStartupOnboardingTwoPane(width = 1_200.dp, height = 800.dp))
        assertTrue(isStartupOnboardingTwoPane(width = 720.dp, height = 320.dp))
    }

    @Test
    fun `only a short two pane sidebar tightens the heading`() {
        assertTrue(isStartupOnboardingShortSidebar(twoPane = true, height = 552.dp))
        assertFalse(isStartupOnboardingShortSidebar(twoPane = true, height = 752.dp))
        assertFalse(isStartupOnboardingShortSidebar(twoPane = false, height = 552.dp))
    }

    @Test
    fun `rotating away from the lyrics step falls back to now playing instead of skipping ahead`() {
        val landscape = startupStepsFor(tabletLandscape = true)
        val portrait = startupStepsFor(tabletLandscape = false)
        assertEquals(StartupStep.PlaybackControls, resolveStartupStepInSequence(StartupStep.Lyrics, landscape))
        assertEquals(StartupStep.Lyrics, resolveStartupStepInSequence(StartupStep.Lyrics, portrait))
        StartupStep.entries.filter { it != StartupStep.Lyrics }.forEach { step ->
            assertEquals(step, resolveStartupStepInSequence(step, landscape))
        }
    }

    @Test
    fun `changing the now playing size carries the lyrics size only while they match`() {
        val following = PlaybackControlLayoutPreferences()
        assertEquals(
            PlaybackControlLayoutPreferences(nowPlayingSize = PlaybackControlSize.LARGE, lyricsSize = PlaybackControlSize.LARGE),
            linkStartupLyricsControlSize(following, following.copy(nowPlayingSize = PlaybackControlSize.LARGE))
        )
        val custom = PlaybackControlLayoutPreferences(lyricsSize = PlaybackControlSize.SMALL)
        assertEquals(
            custom.copy(nowPlayingSize = PlaybackControlSize.LARGE),
            linkStartupLyricsControlSize(custom, custom.copy(nowPlayingSize = PlaybackControlSize.LARGE))
        )
        val placementOnly = following.copy(nowPlayingPlacement = NowPlayingControlPlacement.BOTTOM)
        assertEquals(placementOnly, linkStartupLyricsControlSize(following, placementOnly))
    }

    @Test
    fun `a choice joins the rail after its own step and leaves it on the finish page`() {
        assertFalse(isStartupChoiceInRail(StartupChoice.Language, StartupStep.Language))
        assertTrue(isStartupChoiceInRail(StartupChoice.Language, StartupStep.Style))
        assertFalse(isStartupChoiceInRail(StartupChoice.Style, StartupStep.Style))
        assertTrue(isStartupChoiceInRail(StartupChoice.Motion, StartupStep.Privacy))
        StartupChoice.entries.forEach { choice ->
            assertFalse(isStartupChoiceInRail(choice, StartupStep.LearningGuide))
        }
    }

    @Test
    fun `privacy countdown progress fills the button as seconds pass`() {
        assertEquals(0f, resolveStartupPrivacyCountdownProgress(STARTUP_PRIVACY_COUNTDOWN_SECONDS), 0.0001f)
        assertEquals(0.6f, resolveStartupPrivacyCountdownProgress(2, 5), 0.0001f)
        assertEquals(1f, resolveStartupPrivacyCountdownProgress(0), 0.0001f)
        assertEquals(1f, resolveStartupPrivacyCountdownProgress(3, 0), 0.0001f)
    }

    @Test
    fun `notification permission confirmation ignores taps outside the dialog`() {
        assertFalse(StartupNotificationWarningDialogProperties.dismissOnClickOutside)
        assertTrue(StartupNotificationWarningDialogProperties.dismissOnBackPress)
    }
}
