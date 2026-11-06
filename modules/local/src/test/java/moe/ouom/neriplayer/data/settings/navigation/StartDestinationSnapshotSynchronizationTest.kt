package moe.ouom.neriplayer.data.settings.navigation

import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.spy

class StartDestinationSnapshotSynchronizationTest {
    private val stores = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(stores)

    @Test
    fun `updates keep their mutation and cache publication in order`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val firstChanged = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val mutations = mutableListOf<String>()
            val first = launch {
                synchronizeStartDestinationSnapshot(context) {
                    setRoute("library")
                    mutations += "library"
                    firstChanged.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstChanged.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                synchronizeStartDestinationSnapshot(context) {
                    mutations += "settings"
                    setRoute("settings")
                }
            }
            try {
                assertEquals(listOf("library"), mutations)
            } finally {
                releaseFirst.complete(Unit)
                first.join()
                second.join()
            }

            assertEquals(listOf("library", "settings"), mutations)
            assertEquals(StartDestinationSnapshot("settings"), readCachedStartDestinationSnapshot(context))
        }
    }

    @Test
    fun `cancellation after a settings commit still reconciles the cache`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            persistStartDestinationSnapshot(context, StartDestinationSnapshot("home"))
            val committed = CompletableDeferred<Unit>()
            val updating = launch {
                synchronizeStartDestinationSnapshot(context) {
                    setRoute("library")
                    committed.complete(Unit)
                    awaitCancellation()
                }
            }
            committed.await()

            updating.cancelAndJoin()

            assertTrue(updating.isCancelled)
            assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
        }
    }

    @Test
    fun `cancelling a waiting update does not run its mutation or retain the lock`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val ownerEntered = CompletableDeferred<Unit>()
            val releaseOwner = CompletableDeferred<Unit>()
            var waitingMutationRan = false
            val owner = launch {
                synchronizeStartDestinationSnapshot(context) {
                    ownerEntered.complete(Unit)
                    releaseOwner.await()
                    setRoute("library")
                }
            }
            ownerEntered.await()
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                synchronizeStartDestinationSnapshot(context) {
                    waitingMutationRan = true
                    setRoute("settings")
                }
            }
            try {
                waiting.cancelAndJoin()
                assertTrue(waiting.isCancelled)
                assertFalse(waitingMutationRan)
            } finally {
                releaseOwner.complete(Unit)
                owner.join()
            }

            synchronizeStartDestinationSnapshot(context)
            assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
        }
    }

    @Test
    fun `failed refresh propagates and keeps a missing cache unknown`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val failure = IOException("settings unavailable")
            failSettingsRead(failure)

            val actual = runCatching { synchronizeStartDestinationSnapshot(context) }.exceptionOrNull()

            assertRefreshFailure(failure, actual)
            assertNull(readCachedStartDestinationSnapshot(context))
        }
    }

    @Test
    fun `failed refresh preserves an existing cache instead of writing defaults`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            persistStartDestinationSnapshot(context, StartDestinationSnapshot("library"))
            val failure = IOException("settings unavailable")
            failSettingsRead(failure)

            val actual = runCatching { synchronizeStartDestinationSnapshot(context) }.exceptionOrNull()

            assertRefreshFailure(failure, actual)
            assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
        }
    }

    @Test
    fun `failed reconciliation does not replace the original update failure`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val updateFailure = IllegalStateException("update failed")
            val refreshFailure = IOException("settings unavailable")
            failSettingsRead(refreshFailure)

            val actual = runCatching {
                synchronizeStartDestinationSnapshot(context) { throw updateFailure }
            }.exceptionOrNull()

            assertSame(updateFailure, actual)
            assertEquals(1, updateFailure.suppressed.size)
            assertRefreshFailure(refreshFailure, updateFailure.suppressed.single())
            assertNull(readCachedStartDestinationSnapshot(context))
        }
    }

    private suspend fun setRoute(route: String) {
        context.dataStore.updateData {
            mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to route)
        }
    }

    private suspend fun failSettingsRead(failure: IOException) {
        val source = spy(mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to "settings"))
        doAnswer { throw failure }.`when`(source).get(SettingsKeys.DEFAULT_START_DESTINATION)
        context.dataStore.updateData { source }
    }

    private fun assertRefreshFailure(expected: IOException, actual: Throwable?) {
        assertTrue("refresh must preserve the IOException type", actual is IOException)
        assertEquals(expected.message, actual!!.message)
        assertTrue(
            "refresh failure must retain the original cause",
            generateSequence(actual) { it.cause }.any { it === expected }
        )
    }
}
