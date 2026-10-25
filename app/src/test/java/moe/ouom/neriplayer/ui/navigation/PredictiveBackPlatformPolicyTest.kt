package moe.ouom.neriplayer.ui.navigation

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import moe.ouom.neriplayer.util.platform.ColorOsPredictiveBackDetector
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PredictiveBackPlatformPolicyTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val pageOwner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }
    private var mounted by mutableStateOf(true)
    private var detectSupport by mutableStateOf<suspend () -> Boolean?>({ null })
    private lateinit var supportState: State<Boolean>
    private val previewSupported get() = supportState.value
    private var policyDisposed = false

    @After
    fun disposePolicy() {
        unmountPolicy()
        composeRule.runOnIdle { pageOwner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    @Test
    fun `mounted started composition recovers after its first unknown batch`() {
        var reads = 0
        var available = false
        val detector = ColorOsPredictiveBackDetector {
            reads++
            if (available) true else null
        }
        render(detector::detect)
        assertEquals(0, reads)

        moveTo(Lifecycle.State.STARTED)
        advanceClock(1_100L)
        assertEquals(3, reads)
        assertFalse(previewSupported)

        available = true
        advanceClock(5_100L)

        assertTrue(previewSupported)
        assertEquals(4, reads)
    }

    @Test
    fun `two unknown batches stop until the next foreground activation`() {
        var reads = 0
        var available = false
        val detector = ColorOsPredictiveBackDetector {
            reads++
            if (available) true else null
        }
        render(detector::detect)
        moveTo(Lifecycle.State.STARTED)
        advanceClock(8_000L)
        assertEquals(6, reads)
        assertFalse(previewSupported)

        advanceClock(60_000L)
        assertEquals(6, reads)
        moveTo(Lifecycle.State.CREATED)
        available = true
        moveTo(Lifecycle.State.STARTED)

        assertTrue(previewSupported)
        assertEquals(7, reads)
    }

    @Test
    fun `confirmed unsupported result skips detection on later activations`() {
        assertConfirmedResultIsRetained(false)
    }

    @Test
    fun `confirmed supported result skips detection on later activations`() {
        assertConfirmedResultIsRetained(true)
    }

    @Test
    fun `stopping cancels the delayed batch and permits a fresh activation`() {
        var reads = 0
        var available = false
        val detector = ColorOsPredictiveBackDetector {
            reads++
            if (available) true else null
        }
        render(detector::detect)
        moveTo(Lifecycle.State.STARTED)
        advanceClock(1_100L)
        assertEquals(3, reads)

        moveTo(Lifecycle.State.CREATED)
        advanceClock(60_000L)
        assertEquals(3, reads)

        available = true
        moveTo(Lifecycle.State.STARTED)
        assertTrue(previewSupported)
        assertEquals(4, reads)
    }

    @Test
    fun `stopping cancels an active probe before restarting detection`() {
        var reads = 0
        var cancelledReads = 0
        var available = false
        val detector = ColorOsPredictiveBackDetector {
            reads++
            if (available) {
                true
            } else {
                try {
                    awaitCancellation()
                } finally {
                    cancelledReads++
                }
            }
        }
        render(detector::detect)
        moveTo(Lifecycle.State.STARTED)
        assertEquals(1, reads)

        moveTo(Lifecycle.State.CREATED)
        assertEquals(1, cancelledReads)
        assertFalse(previewSupported)

        available = true
        moveTo(Lifecycle.State.STARTED)
        assertTrue(previewSupported)
        assertEquals(2, reads)
    }

    @Test
    fun `disposing cancels an active probe and leaves no delayed retry`() {
        var reads = 0
        var cancelledReads = 0
        val detector = ColorOsPredictiveBackDetector {
            reads++
            try {
                awaitCancellation()
            } finally {
                cancelledReads++
            }
        }
        render(detector::detect)
        moveTo(Lifecycle.State.STARTED)
        assertEquals(1, reads)

        unmountPolicy()
        assertEquals(1, cancelledReads)

        advanceClock(60_000L)
        assertEquals(1, reads)
        assertFalse(previewSupported)
    }

    @Test
    fun `updated detector is used by the delayed batch without restarting the budget`() {
        var firstReads = 0
        var nextReads = 0
        render {
            firstReads++
            null
        }
        moveTo(Lifecycle.State.STARTED)
        assertEquals(1, firstReads)

        composeRule.runOnIdle {
            detectSupport = {
                nextReads++
                true
            }
        }
        advanceClock(1_000L)
        assertEquals(0, nextReads)
        assertFalse(previewSupported)

        advanceClock(5_100L)
        assertEquals(1, nextReads)
        assertEquals(1, firstReads)
        assertTrue(previewSupported)
    }

    private fun assertConfirmedResultIsRetained(support: Boolean) {
        var reads = 0
        render {
            reads++
            support
        }
        moveTo(Lifecycle.State.STARTED)
        assertEquals(support, previewSupported)
        assertEquals(1, reads)

        advanceClock(60_000L)
        moveTo(Lifecycle.State.CREATED)
        moveTo(Lifecycle.State.STARTED)
        advanceClock(60_000L)

        assertEquals(support, previewSupported)
        assertEquals(1, reads)
    }

    private fun render(probe: suspend () -> Boolean?) {
        detectSupport = probe
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides pageOwner) {
                if (mounted) {
                    val support = rememberPredictiveBackPlatformSupport(detectSupport)
                    SideEffect { supportState = support }
                    DisposableEffect(Unit) {
                        onDispose { policyDisposed = true }
                    }
                }
            }
        }
        moveTo(Lifecycle.State.CREATED)
    }

    private fun moveTo(state: Lifecycle.State) {
        composeRule.runOnIdle { pageOwner.lifecycle.currentState = state }
        advanceClock(32L)
    }

    private fun advanceClock(millis: Long) {
        composeRule.runOnIdle { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeBy(millis)
        composeRule.waitForIdle()
    }

    private fun unmountPolicy() {
        composeRule.runOnIdle {
            mounted = false
            Snapshot.sendApplyNotifications()
        }
        composeRule.mainClock.advanceTimeUntil { policyDisposed }
        composeRule.waitForIdle()
    }
}
