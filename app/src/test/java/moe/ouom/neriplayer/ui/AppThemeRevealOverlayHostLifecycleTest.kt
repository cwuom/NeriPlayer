package moe.ouom.neriplayer.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.ui.theme.reveal.AppThemeRevealOverlayHost
import moe.ouom.neriplayer.ui.theme.reveal.AppThemeRevealPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppThemeRevealOverlayHostLifecycleTest {
    @Test
    fun absentPresentationDoesNotCompleteAToken() = runTest {
        val completed = mutableListOf<Int>()
        val fixture = HostCompositionFixture(this) { completed += it }
        try {
            fixture.start()
            fixture.pump()
            fixture.startRadius.floatValue = 9f
            fixture.pump()
            fixture.dispose()
            fixture.pump()

            assertTrue(completed.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun missingSnapshotCompletesEachTokenOnceAcrossRecompositionAndDisposal() = runTest {
        val completed = mutableListOf<Int>()
        val fixture = HostCompositionFixture(this) { completed += it }
        fixture.presentation.value = presentation(7)
        try {
            fixture.start()
            fixture.pump()
            assertEquals(listOf(7), completed)
            val initialCompositions = fixture.compositions

            fixture.startRadius.floatValue = 12f
            fixture.presentation.value = presentation(7).copy(fallbackColorArgb = 0xff112233.toInt())
            fixture.pump()
            assertTrue("the same token must survive a real recomposition", fixture.compositions > initialCompositions)
            assertEquals(listOf(7), completed)

            fixture.presentation.value = presentation(8).copy(reverse = true)
            fixture.pump()
            assertEquals(listOf(7, 8), completed)

            fixture.dispose()
            fixture.pump()
            assertEquals("disposal must not complete either token again", listOf(7, 8), completed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ownerCanClearPresentationFromTheCompletionCallback() = runTest {
        val completed = mutableListOf<Int>()
        lateinit var fixture: HostCompositionFixture
        fixture = HostCompositionFixture(this) { token ->
            completed += token
            fixture.presentation.value = null
        }
        fixture.presentation.value = presentation(31)
        try {
            fixture.start()
            fixture.pump()

            assertEquals(listOf(31), completed)
            assertNull(fixture.presentation.value)
            fixture.dispose()
            fixture.pump()
            assertEquals(listOf(31), completed)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disposalBeforeEffectLaunchDoesNotFinishAStaleToken() = runTest {
        val completed = mutableListOf<Int>()
        val fixture = HostCompositionFixture(this) { completed += it }
        fixture.presentation.value = presentation(44)
        try {
            fixture.start()
            assertTrue(completed.isEmpty())
            fixture.dispose()
            fixture.pump()

            assertTrue("a disposed presentation must not finish later", completed.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private fun presentation(token: Int) = AppThemeRevealPresentation(
        originInWindow = Offset(12f, 30f),
        fallbackColorArgb = 0xffeeeeee.toInt(),
        captureToken = token
    )

    private class HostCompositionFixture(
        private val scope: TestScope,
        private val onFinished: (Int) -> Unit
    ) {
        val presentation = mutableStateOf<AppThemeRevealPresentation?>(null)
        val startRadius = mutableFloatStateOf(1f)
        var compositions = 0
            private set
        private val frameClock = BroadcastFrameClock()
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        private var disposed = false

        fun start() {
            composition.setContent {
                SideEffect { compositions += 1 }
                AppThemeRevealOverlayHost(
                    presentation = presentation.value,
                    snapshot = null,
                    startRadiusPx = startRadius.floatValue,
                    onFinished = onFinished
                )
            }
        }

        fun pump() {
            repeat(4) {
                scope.runCurrent()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                frameClock.sendFrame(frameTime++)
            }
            scope.runCurrent()
        }

        fun dispose() {
            if (!disposed) {
                disposed = true
                composition.dispose()
            }
        }

        suspend fun close() {
            dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
