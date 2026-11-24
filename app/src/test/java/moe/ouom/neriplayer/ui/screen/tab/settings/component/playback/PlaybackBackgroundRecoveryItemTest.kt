package moe.ouom.neriplayer.ui.screen.tab.settings.component.playback

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.system.BackgroundBehaviorAllowance
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackBackgroundRecoveryItemTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val restricted = BackgroundBehaviorAllowance(ignoringBatteryOptimizations = false, backgroundAppOpsAllowed = true)
    private val allowed = BackgroundBehaviorAllowance(ignoringBatteryOptimizations = true, backgroundAppOpsAllowed = true)

    @Test
    fun `restricted state asks the user to turn off battery optimization`() {
        val requests = mutableListOf<BackgroundBehaviorAllowance>()
        setContent(readAllowance = { restricted }, onRequest = { requests += it })

        composeRule.onNodeWithText(text(CoreCommonR.string.settings_playback_background_recovery_restricted))
            .assertExists()
        composeRule.onNodeWithTag(PLAYBACK_BACKGROUND_RECOVERY_ITEM_TAG).performClick()

        assertEquals(listOf(restricted), requests)
    }

    @Test
    fun `allowed state reports recovery and ignores taps`() {
        val requests = mutableListOf<BackgroundBehaviorAllowance>()
        setContent(readAllowance = { allowed }, onRequest = { requests += it })

        composeRule.onNodeWithText(text(CoreCommonR.string.settings_playback_background_recovery_allowed))
            .assertExists()
        composeRule.onNodeWithTag(PLAYBACK_BACKGROUND_RECOVERY_ITEM_TAG).performClick()

        assertEquals(emptyList<BackgroundBehaviorAllowance>(), requests)
    }

    @Test
    fun `returning from system settings refreshes the state`() {
        var current = restricted
        val owner = TestLifecycleOwner()
        setContent(readAllowance = { current }, onRequest = {}, lifecycleOwner = owner)
        composeRule.onNodeWithText(text(CoreCommonR.string.settings_playback_background_recovery_restricted))
            .assertExists()

        current = allowed
        composeRule.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            owner.registry.currentState = Lifecycle.State.RESUMED
        }

        composeRule.onNodeWithText(text(CoreCommonR.string.settings_playback_background_recovery_allowed))
            .assertExists()
    }

    private fun setContent(
        readAllowance: () -> BackgroundBehaviorAllowance,
        onRequest: (BackgroundBehaviorAllowance) -> Unit,
        lifecycleOwner: TestLifecycleOwner = TestLifecycleOwner()
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                MaterialTheme {
                    PlaybackBackgroundRecoveryItem(readAllowance = readAllowance, onRequest = onRequest)
                }
            }
        }
    }

    private fun text(id: Int): String = context.getString(id)

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.CREATED }
        override val lifecycle: Lifecycle get() = registry
    }
}
