package moe.ouom.neriplayer.core.startup.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSyncWarningCoordinatorTest {
    @Test
    fun `returns warning result from planner`() = runTest {
        val store = FakeStartupSyncWarningStore(
            state = StartupSyncWarningState(
                hasRepoInfo = true,
                hasSyncHistory = false,
                isConfigured = false,
                isDismissed = false
            )
        )

        val result = StartupSyncWarningCoordinator(store).check(hasShownWarning = false)

        assertEquals(
            StartupSyncWarningCheckResult(
                showWarning = true,
                hasShownWarning = true
            ),
            result
        )
        assertEquals(emptyList<Boolean>(), store.dismissedWrites)
    }

    @Test
    fun `resets dismissed marker when configuration becomes valid`() = runTest {
        val store = FakeStartupSyncWarningStore(
            state = StartupSyncWarningState(
                hasRepoInfo = true,
                hasSyncHistory = true,
                isConfigured = true,
                isDismissed = true
            )
        )

        val result = StartupSyncWarningCoordinator(store).check(hasShownWarning = true)

        assertEquals(
            StartupSyncWarningCheckResult(
                showWarning = false,
                hasShownWarning = false
            ),
            result
        )
        assertEquals(listOf(false), store.dismissedWrites)
    }

    @Test
    fun `dismiss reminder writes dismissed marker`() = runTest {
        val store = FakeStartupSyncWarningStore(
            state = StartupSyncWarningState(
                hasRepoInfo = true,
                hasSyncHistory = false,
                isConfigured = false,
                isDismissed = false
            )
        )

        StartupSyncWarningCoordinator(store).dismissReminder()

        assertEquals(listOf(true), store.dismissedWrites)
    }

    private class FakeStartupSyncWarningStore(
        private val state: StartupSyncWarningState
    ) : StartupSyncWarningStore {
        val dismissedWrites = mutableListOf<Boolean>()

        override suspend fun loadState(): StartupSyncWarningState = state

        override suspend fun setDismissed(dismissed: Boolean) {
            dismissedWrites += dismissed
        }
    }
}
