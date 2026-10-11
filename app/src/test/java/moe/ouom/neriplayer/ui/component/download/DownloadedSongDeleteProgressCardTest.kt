package moe.ouom.neriplayer.ui.component.download

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeletePhase
import moe.ouom.neriplayer.data.model.download.DownloadedSongDeleteProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadedSongDeleteProgressCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `failed deletion shows failure count and dismisses by delete id`() {
        var dismissedId: Long? = null
        val progress = DownloadedSongDeleteProgress(
            deleteId = 42L,
            phase = DownloadedSongDeletePhase.FAILED,
            requestedSongCount = 3,
            totalReferenceCount = 5,
            completedReferenceCount = 4,
            failedReferenceCount = 1
        )

        composeRule.setContent {
            DownloadedSongDeleteProgressCard(
                progress = progress,
                failureDismissed = false,
                onDismissFailure = { dismissedId = it }
            )
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.download_delete_phase_failed))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.download_delete_failed_files, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.action_close))
            .performClick()
        assertEquals(42L, dismissedId)
    }

    @Test
    fun `pending request without progress shows preparing message`() {
        composeRule.setContent {
            DownloadedSongDeleteProgressCard(
                progress = null,
                failureDismissed = false,
                onDismissFailure = {},
                requestedSongCount = 2
            )
        }

        composeRule.onNodeWithText(context.getString(CoreCommonR.string.download_delete_phase_preparing))
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.resources.getQuantityString(CoreCommonR.plurals.download_delete_in_progress_message, 2, 2)
        ).assertIsDisplayed()
    }
}
