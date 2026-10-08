package moe.ouom.neriplayer.core.startup.player

import android.app.Application
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.player.persistence.RestoredPlayerStateSnapshot
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackPreferenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

class PlayerPreloadedInitializerTest {

    @Test
    fun `preferences and restored state are read on the io dispatcher before main thread init`() = runTest {
        val app = mock(Application::class.java)
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val preferences = PlaybackPreferenceSnapshot(
            keepLastPlaybackProgress = false,
            keepPlaybackModeState = true
        )
        val restored = restoredSnapshot()
        val events = mutableListOf<String>()
        var initializedWith: Triple<Application, PlaybackPreferenceSnapshot, RestoredPlayerStateSnapshot?>? = null
        val initializer = PlayerPreloadedInitializer(
            app = app,
            ioDispatcher = ioDispatcher,
            readPreferences = { application ->
                assertSame(app, application)
                events += "read@${currentDispatcherName(ioDispatcher)}"
                preferences
            },
            preloadState = { _, readPreferences ->
                events += "preload@${currentDispatcherName(ioDispatcher)}:" +
                    "${readPreferences.keepLastPlaybackProgress}/${readPreferences.keepPlaybackModeState}"
                restored
            },
            initializePlayer = { application, readPreferences, restoredState ->
                events += "init"
                initializedWith = Triple(application, readPreferences, restoredState)
            }
        )

        val result = initializer.initialize(beforeInitialize = { events += "frame" })

        assertSame(preferences, result)
        assertEquals(
            listOf("read@io", "preload@io:false/true", "frame", "init"),
            events
        )
        assertSame(app, initializedWith?.first)
        assertSame(preferences, initializedWith?.second)
        assertSame(restored, initializedWith?.third)
    }

    @Test
    fun `missing restored state still initializes the player`() = runTest {
        val app = mock(Application::class.java)
        var initializedState: RestoredPlayerStateSnapshot? = restoredSnapshot()
        var initCalls = 0
        val initializer = PlayerPreloadedInitializer(
            app = app,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            readPreferences = { PlaybackPreferenceSnapshot() },
            preloadState = { _, _ -> null },
            initializePlayer = { _, _, restoredState ->
                initCalls += 1
                initializedState = restoredState
            }
        )

        initializer.initialize()

        assertEquals(1, initCalls)
        assertEquals(null, initializedState)
    }

    private suspend fun currentDispatcherName(ioDispatcher: CoroutineDispatcher): String {
        return if (coroutineContext[ContinuationInterceptor] === ioDispatcher) "io" else "other"
    }

    private fun restoredSnapshot() = RestoredPlayerStateSnapshot(
        playlist = emptyList(),
        currentIndex = 0,
        currentMediaUrl = null,
        repeatMode = 0,
        shuffleEnabled = false,
        shuffleRestorePlaylist = null,
        shuffleRestoreIndex = -1,
        resumePositionMs = 0L,
        shouldResumePlayback = false,
        originalPlaylistSize = 0,
        persistedIndex = 0
    )
}
