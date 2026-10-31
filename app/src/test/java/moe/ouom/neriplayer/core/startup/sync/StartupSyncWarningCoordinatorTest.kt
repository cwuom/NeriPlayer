package moe.ouom.neriplayer.core.startup.sync

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSyncWarningCoordinatorTest {
    @Test
    fun `unavailable secure storage preserves reminder state and can be retried`() = runTest {
        val store = FakeStartupSyncWarningStore(StartupSyncWarningState(true, true, false, false))
        store.readFailure = SyncStorageUnavailableException()
        val coordinator = StartupSyncWarningCoordinator(store)

        assertEquals(StartupSyncWarningCheckResult(false, false), coordinator.check(false))
        assertEquals(emptyList<Boolean>(), store.dismissedWrites)
        store.readFailure = null
        assertEquals(StartupSyncWarningCheckResult(true, true), coordinator.check(false))
    }

    @Test
    fun `unavailable dismissed marker writes cannot crash startup or dismiss the reminder`() = runTest {
        val store = FakeStartupSyncWarningStore(StartupSyncWarningState(true, true, true, true))
        store.writeFailure = SyncStorageUnavailableException()
        val coordinator = StartupSyncWarningCoordinator(store)

        assertEquals(StartupSyncWarningCheckResult(false, true), coordinator.check(true))
        coordinator.dismissReminder()

        assertEquals(emptyList<Boolean>(), store.dismissedWrites)
    }

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
        var readFailure: SyncStorageUnavailableException? = null
        var writeFailure: SyncStorageUnavailableException? = null

        override suspend fun loadState(): StartupSyncWarningState {
            readFailure?.let { throw it }
            return state
        }

        override suspend fun setDismissed(dismissed: Boolean) {
            writeFailure?.let { throw it }
            dismissedWrites += dismissed
        }
    }
}
