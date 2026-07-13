package moe.ouom.neriplayer.core.player.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackServiceIdleShutdownCoordinatorTest {
    @Test
    fun `eligible runtime stops after delay`() = runTest {
        var stoppedStartId: Int? = null
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { true },
            currentStartId = { 7 },
            onShutdown = { stoppedStartId = it },
        )

        coordinator.refresh()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(7, stoppedStartId)
    }

    @Test
    fun `new start id restarts full idle window`() = runTest {
        var startId = 1
        var stoppedStartId: Int? = null
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { true },
            currentStartId = { startId },
            onShutdown = { stoppedStartId = it },
        )

        coordinator.refresh()
        advanceTimeBy(500L)
        startId = 2
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(null, stoppedStartId)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, stoppedStartId)
    }

    @Test
    fun `ineligible runtime cancels pending shutdown`() = runTest {
        var eligible = true
        var shutdownCount = 0
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { eligible },
            currentStartId = { 3 },
            onShutdown = { shutdownCount += 1 },
        )

        coordinator.refresh()
        eligible = false
        coordinator.refresh()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(0, shutdownCount)
    }

    @Test
    fun `changing delay restarts full idle window`() = runTest {
        var shutdownCount = 0
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { true },
            currentStartId = { 4 },
            onShutdown = { shutdownCount += 1 },
        )

        coordinator.refresh()
        advanceTimeBy(500L)
        coordinator.updateDelayMs(2_000L)
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(0, shutdownCount)

        advanceTimeBy(500L)
        runCurrent()
        assertEquals(1, shutdownCount)
    }

    @Test
    fun `zero delay disables pending shutdown`() = runTest {
        var shutdownCount = 0
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { true },
            currentStartId = { 5 },
            onShutdown = { shutdownCount += 1 },
        )

        coordinator.refresh()
        coordinator.updateDelayMs(0L)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(0, shutdownCount)
    }

    @Test
    fun `same delay keeps the original idle window`() = runTest {
        var shutdownCount = 0
        val coordinator = PlaybackServiceIdleShutdownCoordinator(
            scope = this,
            delayMs = 1_000L,
            isEligible = { true },
            currentStartId = { 6 },
            onShutdown = { shutdownCount += 1 },
        )

        coordinator.refresh()
        advanceTimeBy(500L)
        coordinator.updateDelayMs(1_000L)
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(1, shutdownCount)
    }
}
