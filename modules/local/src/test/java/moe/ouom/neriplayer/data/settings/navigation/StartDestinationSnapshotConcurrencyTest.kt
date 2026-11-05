package moe.ouom.neriplayer.data.settings.navigation

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy

class StartDestinationSnapshotConcurrencyTest {
    private val stores = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(stores)

    @Test
    fun `delayed warmup does not overwrite a newer explicit cache write`() = runBlocking {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val gate = installDelayedSettingsRead()
            val callerThread = Thread.currentThread()
            val finished = CountDownLatch(1)
            val cache = stores[SNAPSHOT_CACHE]
            val observedCache = object : SharedPreferences by cache {
                private fun finishBackgroundAccess() {
                    if (Thread.currentThread() !== callerThread && gate.released.count == 0L) {
                        finished.countDown()
                    }
                }

                override fun contains(key: String): Boolean {
                    return cache.contains(key).also { present ->
                        if (present) finishBackgroundAccess()
                    }
                }

                override fun edit(): SharedPreferences.Editor {
                    val editor = cache.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun apply() {
                            editor.apply()
                            finishBackgroundAccess()
                        }
                    }
                }
            }
            doAnswer { invocation ->
                val name = invocation.getArgument<String>(0)
                if (name == SNAPSHOT_CACHE) observedCache else stores[name]
            }.`when`(context).getSharedPreferences(anyString(), anyInt())

            try {
                mockStatic(Looper::class.java).use { looper ->
                    looper.`when`<Looper> { Looper.myLooper() }.thenReturn(null)
                    looper.`when`<Looper> { Looper.getMainLooper() }.thenReturn(null)
                    assertNull(readStartDestinationSnapshotOrNull(context))
                }
                gate.awaitRead()
                persistNewerRoute()
                gate.release()
                assertTrue("warmup must finish accessing the cache", finished.await(10, TimeUnit.SECONDS))

                assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
            } finally {
                gate.release()
            }
        }
    }

    @Test
    fun `delayed background read returns the newer explicit cache value`() = runBlocking {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val gate = installDelayedSettingsRead()
            val result = FutureTask { readAsBackgroundThread(context) }
            val worker = Thread(result, "start-destination-snapshot-test")
            worker.start()
            try {
                gate.awaitRead()
                persistNewerRoute()
                gate.release()

                assertEquals(StartDestinationSnapshot("library"), result.get(10, TimeUnit.SECONDS))
                assertEquals(StartDestinationSnapshot("library"), readCachedStartDestinationSnapshot(context))
            } finally {
                gate.release()
                worker.join(10_000)
                assertFalse("background snapshot reader must finish", worker.isAlive)
            }
        }
    }

    @Test
    fun `failed background settings read leaves the missing cache unknown`() = runBlocking {
        IsolatedSettingsDataStore.withEmptySettings(context) {
            val source = spy(mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to "settings"))
            doAnswer { throw IOException("settings unavailable") }.`when`(source)
                .get(SettingsKeys.DEFAULT_START_DESTINATION)
            context.dataStore.updateData { source }

            assertNull(readAsBackgroundThread(context))
            assertNull(readCachedStartDestinationSnapshot(context))
            assertTrue(stores[SNAPSHOT_CACHE].snapshot().isEmpty())
        }
    }

    private suspend fun installDelayedSettingsRead(): SettingsReadGate {
        val source: MutablePreferences = spy(
            mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to "home")
        )
        val gate = SettingsReadGate()
        doAnswer { invocation ->
            val route = invocation.callRealMethod()
            gate.started.countDown()
            check(gate.released.await(10, TimeUnit.SECONDS)) { "settings read was not released" }
            route
        }.`when`(source).get(SettingsKeys.DEFAULT_START_DESTINATION)
        context.dataStore.updateData { source }
        return gate
    }

    private suspend fun persistNewerRoute() {
        context.dataStore.updateData {
            mutablePreferencesOf(SettingsKeys.DEFAULT_START_DESTINATION to "library")
        }
        persistStartDestinationSnapshot(context, StartDestinationSnapshot("library"))
    }

    private fun readAsBackgroundThread(context: Context): StartDestinationSnapshot? {
        return mockStatic(Looper::class.java).use { looper ->
            looper.`when`<Looper> { Looper.myLooper() }.thenReturn(mock(Looper::class.java))
            readStartDestinationSnapshotOrNull(context)
        }
    }

    private class SettingsReadGate {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)

        fun awaitRead() {
            assertTrue("reader must capture the old settings value", started.await(10, TimeUnit.SECONDS))
        }

        fun release() {
            released.countDown()
        }
    }

    private companion object {
        const val SNAPSHOT_CACHE = "start_destination_snapshot_cache"
    }
}
