package moe.ouom.neriplayer.ui.component.download

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeletePhase
import moe.ouom.neriplayer.core.download.model.DownloadedSongDeleteProgress
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedSongDeleteProgressCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun progressRemainsVisibleThroughPhysicalDeletionAndFinalizationThenDisappears() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = mutableStateOf(newProgress())
        val failureDismissed = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                DownloadedSongDeleteProgressCard(
                    progress = progress.value,
                    failureDismissed = failureDismissed.value,
                    onDismissFailure = { failureDismissed.value = true }
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_preparing))
            .assertIsDisplayed()
        composeRule.onNode(progressRange(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()

        composeRule.runOnIdle {
            progress.value = progress.value.copy(
                phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
                totalReferenceCount = 4000,
                completedReferenceCount = 1000
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_deleting_files))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.download_clear_item_progress, 1000, 4000))
            .assertIsDisplayed()
        composeRule.onNode(progressRange(ProgressBarRangeInfo(0.25f, 0f..1f)))
            .assertIsDisplayed()

        composeRule.runOnIdle {
            progress.value = progress.value.copy(
                phase = DownloadedSongDeletePhase.FINALIZING,
                completedReferenceCount = 4000
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_finalizing))
            .assertIsDisplayed()
        composeRule.onNode(progressRange(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        composeRule.onNode(progressRange(ProgressBarRangeInfo(1f, 0f..1f)))
            .assertDoesNotExist()

        composeRule.runOnIdle {
            progress.value = progress.value.copy(phase = DownloadedSongDeletePhase.COMPLETED)
        }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_finalizing))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.download_clear_item_progress, 4000, 4000))
            .assertDoesNotExist()
    }

    @Test
    fun failureStopsTheIndicatorAndCanBeDismissedWithoutChangingTheDeleteTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = mutableStateOf(newProgress().copy(
            phase = DownloadedSongDeletePhase.DELETING_REFERENCES,
            totalReferenceCount = 4000,
            completedReferenceCount = 3998
        ))
        val failureDismissed = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                DownloadedSongDeleteProgressCard(
                    progress = progress.value,
                    failureDismissed = failureDismissed.value,
                    onDismissFailure = { deleteId ->
                        if (deleteId == progress.value.deleteId) failureDismissed.value = true
                    }
                )
            }
        }
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertIsDisplayed()

        composeRule.runOnIdle {
            progress.value = progress.value.copy(
                phase = DownloadedSongDeletePhase.FAILED,
                failedReferenceCount = 2
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.download_delete_failed_files, 2))
            .assertIsDisplayed()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            progress.value = progress.value.copy(totalReferenceCount = 0, completedReferenceCount = 0)
        }
        composeRule.onNodeWithText(context.getString(R.string.download_clear_item_progress, 0, 0))
            .assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.download_delete_failed_files, 2))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_close))
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_failed))
            .assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(DownloadedSongDeletePhase.FAILED, progress.value.phase)
            assertEquals(2, progress.value.failedReferenceCount)
            progress.value = progress.value.copy(
                phase = DownloadedSongDeletePhase.VERIFYING_REFERENCES,
                failedReferenceCount = 0
            )
            failureDismissed.value = false
        }
        composeRule.onNodeWithText(context.getString(R.string.download_delete_phase_verifying))
            .assertIsDisplayed()
        composeRule.onNode(progressRange(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    private fun progressRange(range: ProgressBarRangeInfo): SemanticsMatcher {
        return SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, range)
    }

    private fun newProgress() = DownloadedSongDeleteProgress(
        deleteId = 1L,
        phase = DownloadedSongDeletePhase.PREPARING,
        requestedSongCount = 1000,
        fullLibraryDelete = true
    )
}
