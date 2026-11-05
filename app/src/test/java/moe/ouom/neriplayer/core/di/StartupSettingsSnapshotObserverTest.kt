package moe.ouom.neriplayer.core.di

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.startup.safemode.SafeModeResetActions
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.navigation.readStartDestinationSnapshotOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StartupSettingsSnapshotObserverTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun pendingBootstrapSnapshotCannotOverwriteANewerStartDestination() = runBlocking {
        verifyPendingObserverKeepsNewerRoute("bootstrap_settings_snapshot")
    }

    @Test
    fun pendingPlaybackSnapshotCannotOverwriteANewerStartDestination() = runBlocking {
        verifyPendingObserverKeepsNewerRoute("playback_snapshot_cache")
    }

    @Test
    fun pendingObserverCannotUndoASafeModeSettingsReset() = runBlocking {
        verifyPendingObserverKeepsNewerRoute(
            blockedSnapshot = "playback_snapshot_cache",
            initialRoute = "library",
            expectedRoute = "home"
        ) { context ->
            SafeModeResetActions(context).resetAppSettings()
        }
    }

    private suspend fun verifyPendingObserverKeepsNewerRoute(
        blockedSnapshot: String,
        initialRoute: String = "home",
        expectedRoute: String = "library",
        changeSettings: suspend (Context) -> Unit = {
            SettingsRepository(it).setDefaultStartDestination("library")
        }
    ) {
        val savedSettings = application.dataStore.data.first()
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observerThread = AtomicReference<Thread>()
        val paused = AtomicBoolean(false)
        val context = object : ContextWrapper(application) {
            override fun getApplicationContext(): Context = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name == blockedSnapshot && Thread.currentThread() === observerThread.get() &&
                    paused.compareAndSet(false, true)) {
                    captured.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "pending observer was not released" }
                }
                return super.getSharedPreferences("startup-observer-$blockedSnapshot-$name", mode)
            }
        }
        val repository = SettingsRepository(context)
        var worker: Thread? = null
        try {
            repository.setDefaultStartDestination(initialRoute)
            val oldPreferences = context.dataStore.data.first()
            val result = FutureTask {
                runBlocking { persistStartupSettingsSnapshots(context, oldPreferences) }
            }
            worker = Thread(result, "startup-settings-snapshot-observer-test").also {
                observerThread.set(it)
                it.start()
            }
            assertTrue("observer must pause while persisting its captured preferences",
                captured.await(10, TimeUnit.SECONDS))

            changeSettings(context)
            assertEquals(StartDestinationSnapshot(expectedRoute), readStartDestinationSnapshotOrNull(context))
            release.countDown()
            result.get(10, TimeUnit.SECONDS)

            assertEquals(expectedRoute,
                context.dataStore.data.first()[SettingsKeys.DEFAULT_START_DESTINATION] ?: "home")
            assertEquals(StartDestinationSnapshot(expectedRoute), readStartDestinationSnapshotOrNull(context))
        } finally {
            release.countDown()
            try {
                worker?.join(10_000)
                assertFalse("owned observer must finish", worker?.isAlive == true)
            } finally {
                application.dataStore.updateData { savedSettings }
                SnapshotCaches.forEach { name ->
                    application.getSharedPreferences("startup-observer-$blockedSnapshot-$name",
                        Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
    }

    private companion object {
        val SnapshotCaches = listOf("bootstrap_settings_snapshot", "playback_snapshot_cache",
            "start_destination_snapshot_cache", "theme_snapshot_cache")
    }
}
