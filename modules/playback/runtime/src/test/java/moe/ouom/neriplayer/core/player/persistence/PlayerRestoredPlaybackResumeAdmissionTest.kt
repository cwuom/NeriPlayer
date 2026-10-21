package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.content.ContentResolver
import android.content.UriPermission
import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.host.PlayerDownloadAccess
import moe.ouom.neriplayer.core.player.host.PlayerEnvironment
import moe.ouom.neriplayer.core.player.host.PlayerListenTogetherAccess
import moe.ouom.neriplayer.core.player.host.PlayerPresentationHost
import moe.ouom.neriplayer.core.player.host.PlayerRepositoryDependencies
import moe.ouom.neriplayer.core.player.playback.playAtIndex
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.ltw.room.ListenTogetherRoomState
import moe.ouom.neriplayer.data.model.ltw.session.ListenTogetherSessionState
import moe.ouom.neriplayer.data.model.playback.RestoredPlaybackState
import moe.ouom.neriplayer.data.model.playback.storage.PlayerLocalPlaybackResolution
import moe.ouom.neriplayer.data.local.media.source.ResolvedLocalMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Answers
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRestoredPlaybackResumeAdmissionTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `folder recovery runs on IO before consuming progress and starting playback on main`() = withFixture {
        assertEquals(PositionMs, manager.resumeRestoredPlaybackIfNeeded())

        assertTrue(providerThreads.isNotEmpty())
        assertTrue(providerThreads.none { it })
        assertEquals(listOf(true), playbackThreads)
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
    }

    @Test
    fun `an IO caller still commits restored playback on the main dispatcher`() = withFixture {
        assertEquals(PositionMs, withContext(Dispatchers.IO) { manager.resumeRestoredPlaybackIfNeeded() })

        assertTrue(providerThreads.none { it })
        assertEquals(listOf(true), playbackThreads)
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
    }

    @Test
    fun `remote restored playback does not inspect the local provider`() = withFixture {
        val remote = song.copy(localFilePath = null, mediaUri = null)
        manager.publishCurrentQueue(listOf(remote), 0)
        manager._currentSongFlow.value = remote

        assertEquals(PositionMs, manager.resumeRestoredPlaybackIfNeeded())

        assertTrue(providerThreads.isEmpty())
        assertEquals(listOf(true), playbackThreads)
    }

    @Test
    fun `unreadable provider keeps progress pending and can resume after authorization recovers`() = withFixture {
        readable.set(false)
        val pending = manager.restoredPlaybackSnapshot()

        assertNull(manager.resumeRestoredPlaybackIfNeeded())

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
        assertTrue(playbackThreads.isEmpty())

        readable.set(true)
        assertEquals(PositionMs, manager.resumeRestoredPlaybackIfNeeded())
        assertEquals(1, playbackThreads.size)
    }

    @Test
    fun `provider permission failure does not consume the pending restore`() = withFixture {
        permissionFailure.set(SecurityException("grant unavailable"))
        val pending = manager.restoredPlaybackSnapshot()

        assertNull(manager.resumeRestoredPlaybackIfNeeded())

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertTrue(playbackThreads.isEmpty())
    }

    @Test
    fun `cancelling a blocked provider probe does not consume the pending restore`() = withFixture {
        val barrier = ProbeBarrier()
        beforeOpen = barrier::awaitRelease
        val pending = manager.restoredPlaybackSnapshot()
        val request = async(start = CoroutineStart.UNDISPATCHED) { manager.resumeRestoredPlaybackIfNeeded() }
        try {
            barrier.entered.await()
            request.cancel()
        } finally {
            barrier.release.countDown()
        }
        request.cancelAndJoin()

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertTrue(playbackThreads.isEmpty())
    }

    @Test
    fun `a manual playback request during the probe rejects the old restore token`() = withFixture {
        assertRejectedAfterProbe { manager.playbackRequestToken += 1 }
    }

    @Test
    fun `replacing the same song identity during the probe rejects the old source snapshot`() = withFixture {
        assertRejectedAfterProbe {
            val replacement = song.copy(mediaUri = "/storage/emulated/0/Music/Other/replacement.mp3")
            manager.publishCurrentQueue(listOf(replacement), 0)
            manager._currentSongFlow.value = replacement
        }
    }

    @Test
    fun `changing queue index during the probe rejects the old queue selection`() = withFixture {
        assertRejectedAfterProbe {
            manager.publishCurrentQueue(listOf(song, song.copy(id = 552L)), 1)
        }
    }

    @Test
    fun `pausing during the probe preserves the paused restore instead of resuming it`() = withFixture {
        assertRejectedAfterProbe { manager.suppressRestoredAutoResume() }
        assertEquals(RestoredPlaybackState.Paused(PositionMs), manager.restoredPlaybackSnapshot())
    }

    @Test
    fun `an equal replacement restore state cannot be consumed by the old probe`() = withFixture {
        assertRejectedAfterProbe { manager.setRestoredPlayback(PositionMs, true) }
        assertEquals(RestoredPlaybackState.ResumePending(PositionMs), manager.restoredPlaybackSnapshot())
    }

    @Test
    fun `release during the probe prevents playback admission`() = withFixture {
        assertRejectedAfterProbe { manager.initialized = false }
    }

    @Test
    fun `concurrent resume requests consume the same pending restore only once`() = withFixture {
        val barrier = ProbeBarrier(expectedProbes = 2)
        beforeOpen = barrier::awaitRelease
        val first = async(start = CoroutineStart.UNDISPATCHED) { manager.resumeRestoredPlaybackIfNeeded() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { manager.resumeRestoredPlaybackIfNeeded() }
        try {
            barrier.entered.await()
        } finally {
            barrier.release.countDown()
        }
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it == PositionMs })
        assertEquals(1, results.count { it == null })
        assertEquals(1, playbackThreads.size)
    }

    @Test
    fun `an existing room listener defers immediately without probing the local provider`() = withFixture {
        session.value = ListenTogetherSessionState(roomId = "room", role = "listener")

        assertNull(manager.resumeRestoredPlaybackIfNeeded())

        assertTrue(providerThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(false), persistedResumeIntent)
    }

    @Test
    fun `becoming a room listener during the probe defers playback and persists a paused intent`() = withFixture {
        val barrier = ProbeBarrier()
        beforeOpen = barrier::awaitRelease
        val request = async(start = CoroutineStart.UNDISPATCHED) { manager.resumeRestoredPlaybackIfNeeded() }
        try {
            barrier.entered.await()
            session.value = ListenTogetherSessionState(roomId = "room", role = "listener")
        } finally {
            barrier.release.countDown()
        }

        assertNull(request.await())
        assertTrue(playbackThreads.isEmpty())
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(false), persistedResumeIntent)
    }

    @Test
    fun `the service scheduling entry reports only an admitted restore`() = withFixture {
        scheduleResume().join()

        assertEquals(1, resumedCallbacks)
        assertEquals(1, playbackThreads.size)
        assertTrue(sourceUnavailableThreads.isEmpty())
    }

    @Test
    fun `the service scheduling entry reports unavailable once and allows an authorization retry`() = withFixture {
        readable.set(false)
        assertUnavailableKeepsPending()

        readable.set(true)
        scheduleResume().join()

        assertEquals(listOf(true), sourceUnavailableThreads)
        assertEquals(1, resumedCallbacks)
        assertEquals(1, playbackThreads.size)
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `a service restore reports lost provider permission without changing its pending state`() = withFixture {
        permissionFailure.set(SecurityException("grant unavailable"))

        assertUnavailableKeepsPending()
    }

    @Test
    fun `a service restore reports a provider exception without changing its pending state`() = withFixture {
        permissionFailure.set(IllegalStateException("provider unavailable"))

        assertUnavailableKeepsPending()
    }

    @Test
    fun `a failed old service probe cannot notify after a newer playback request`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) { manager.playbackRequestToken += 1 }
    }

    @Test
    fun `a failed old service probe cannot notify after the selected queue index changes`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) {
            manager.publishCurrentQueue(listOf(song, song.copy(id = 552L)), 1)
        }
    }

    @Test
    fun `a failed old service probe cannot notify after its queued source changes`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) {
            val replacement = song.copy(localFilePath = "/storage/emulated/0/Music/Other/replacement.mp3")
            manager.publishCurrentQueue(listOf(replacement), 0)
            manager._currentSongFlow.value = replacement
        }
    }

    @Test
    fun `a failed old service probe cannot notify after only the current song changes`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) {
            manager._currentSongFlow.value = song.copy(name = "New current song")
        }
    }

    @Test
    fun `a failed old service probe cannot notify for an equal replacement pending state`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) { manager.setRestoredPlayback(PositionMs, true) }
    }

    @Test
    fun `a failed service probe cannot notify after the user pauses`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) { manager.suppressRestoredAutoResume() }
        assertEquals(RestoredPlaybackState.Paused(PositionMs), manager.restoredPlaybackSnapshot())
    }

    @Test
    fun `a failed service probe cannot notify after the player is released`() = withFixture {
        assertRejectedAfterProbe(unavailable = true) { manager.initialized = false }
    }

    @Test
    fun `joining a listener room during a failed probe defers without an unavailable notification`() = withFixture {
        readable.set(false)
        val barrier = ProbeBarrier()
        beforeOpen = barrier::awaitRelease
        val request = scheduleResume()
        try {
            barrier.entered.await()
            session.value = ListenTogetherSessionState(roomId = "room", role = "listener")
        } finally {
            barrier.release.countDown()
        }
        request.join()

        assertTrue(sourceUnavailableThreads.isEmpty())
        assertEquals(0, resumedCallbacks)
        assertTrue(playbackThreads.isEmpty())
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(false), persistedResumeIntent)
    }

    @Test
    fun `cancelling a failed service probe cannot notify that its source is unavailable`() = withFixture {
        readable.set(false)
        val barrier = ProbeBarrier()
        beforeOpen = barrier::awaitRelease
        val pending = manager.restoredPlaybackSnapshot()
        val request = scheduleResume()
        try {
            barrier.entered.await()
            request.cancel()
        } finally {
            barrier.release.countDown()
        }
        request.cancelAndJoin()

        assertTrue(sourceUnavailableThreads.isEmpty())
        assertEquals(0, resumedCallbacks)
        assertTrue(playbackThreads.isEmpty())
        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
        assertEquals(song, manager._currentSongFlow.value)
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `absent paused and unselected queues do not probe the provider`() = withFixture {
        manager.clearRestoredPlayback()
        assertNull(manager.resumeRestoredPlaybackIfNeeded())
        scheduleResume().join()
        manager.setRestoredPlayback(PositionMs, false)
        assertNull(manager.resumeRestoredPlaybackIfNeeded())
        scheduleResume().join()
        manager.setRestoredPlayback(PositionMs, true)
        manager.publishCurrentQueue(emptyList(), -1)
        assertNull(manager.resumeRestoredPlaybackIfNeeded())
        scheduleResume().join()

        assertTrue(providerThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertEquals(0, resumedCallbacks)
    }

    @Test
    fun `bootstrap source validation checks readable media on IO without resuming or consuming it`() = withFixture {
        val pending = manager.restoredPlaybackSnapshot()
        val persistedAt = manager.lastStatePersistAtMs

        scheduleValidation().join()

        assertTrue(providerThreads.isNotEmpty())
        assertTrue(providerThreads.none { it })
        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
        assertEquals(song, manager.currentSongFlow.value)
        assertEquals(persistedAt, manager.lastStatePersistAtMs)
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `bootstrap source validation reports unavailable media without changing pending progress`() = withFixture {
        readable.set(false)

        assertUnavailableKeepsPending { scheduleValidation() }
    }

    @Test
    fun `bootstrap source validation reports an unreadable file URI without changing pending progress`() = withFixture {
        readable.set(false)
        val fileSong = song.copy(localFilePath = "file://${song.localFilePath}")
        manager.publishCurrentQueue(listOf(fileSong), 0)
        manager._currentSongFlow.value = fileSong

        assertUnavailableKeepsPending { scheduleValidation() }
    }

    @Test
    fun `bootstrap source validation reports permission failures without consuming the pending intent`() = withFixture {
        permissionFailure.set(SecurityException("grant unavailable"))

        assertUnavailableKeepsPending { scheduleValidation() }
    }

    @Test
    fun `bootstrap source validation reports provider exceptions without consuming the pending intent`() = withFixture {
        permissionFailure.set(IllegalStateException("provider unavailable"))

        assertUnavailableKeepsPending { scheduleValidation() }
    }

    @Test
    fun `bootstrap source validation dispatches its unavailable callback on main even from an IO scope`() = withFixture {
        readable.set(false)

        assertUnavailableKeepsPending { scheduleValidation(CoroutineScope(coroutineContext + Dispatchers.IO)) }

        assertTrue(providerThreads.isNotEmpty())
        assertTrue(providerThreads.none { it })
    }

    @Test
    fun `bootstrap validation can recheck authorization without automatically playing readable media`() = withFixture {
        readable.set(false)
        assertUnavailableKeepsPending { scheduleValidation() }
        val pending = manager.restoredPlaybackSnapshot()

        readable.set(true)
        scheduleValidation().join()

        assertEquals(listOf(true), sourceUnavailableThreads)
        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `bootstrap validation opens granted content before treating it as available`() {
        contentOpenFailures().forEach { failure ->
            withFixture {
                selectGrantedContentSource()
                readable.set(false)
                openFailure.set(failure)

                assertUnavailableKeepsPending { scheduleValidation() }

                assertContentOpenedOnIO()
            }
        }
    }

    @Test
    fun `restored resume preserves granted content progress when the document cannot open`() {
        contentOpenFailures().forEach { failure ->
            withFixture {
                selectGrantedContentSource()
                readable.set(false)
                openFailure.set(failure)

                assertUnavailableKeepsPending()

                assertContentOpenedOnIO()
            }
        }
    }

    @Test
    fun `granted readable content validates without consuming progress and resumes on request`() = withFixture {
        selectGrantedContentSource()
        val pending = manager.restoredPlaybackSnapshot()

        scheduleValidation().join()

        assertContentOpenedOnIO()
        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
        assertEquals(PositionMs, manager.resumeRestoredPlaybackIfNeeded())
        assertContentOpenedOnIO()
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertEquals(listOf(true), playbackThreads)
        assertTrue(sourceUnavailableThreads.isEmpty())
    }

    @Test
    fun `restored managed playback accepts a readable replacement from the permitted resolver`() = withFixture {
        readable.set(false)
        val replacement = directory.newFile("migrated.mp3").apply { writeText("audio") }
        permittedResolution.set(PlayerLocalPlaybackResolution.Playable(replacement.path))

        scheduleResume().join()

        assertEquals(listOf(true), playbackThreads)
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertPermittedResolutionOnIO()
        val request = resolutionRequests.single()
        assertEquals(song, request.song)
        assertEquals(song.localFilePath, request.reference)
        assertEquals(song.localFilePath, request.source?.anchorFor(song, request.reference))
    }

    @Test
    fun `bootstrap validation accepts managed rebinds without playing or consuming progress`() = withFixture {
        readable.set(false)
        val replacement = directory.newFile("bootstrap-migrated.mp3").apply { writeText("audio") }
        permittedResolution.set(PlayerLocalPlaybackResolution.Playable(replacement.path))
        val pending = manager.restoredPlaybackSnapshot()

        scheduleValidation().join()

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
        assertPermittedResolutionOnIO()
    }

    @Test
    fun `a readable raw source cannot bypass unavailable permitted playback admission`() {
        unavailablePermittedResolutions().forEach { resolution ->
            withFixture {
                permittedResolution.set(resolution)

                assertUnavailableKeepsPending()

                assertPermittedResolutionOnIO()
            }
        }
    }

    @Test
    fun `bootstrap validation honors unavailable permitted playback admission`() {
        unavailablePermittedResolutions().forEach { resolution ->
            withFixture {
                permittedResolution.set(resolution)

                assertUnavailableKeepsPending { scheduleValidation() }

                assertPermittedResolutionOnIO()
            }
        }
    }

    @Test
    fun `a permitted replacement still has to open before restored progress is consumed`() = withFixture {
        val replacement = directory.root.resolve("missing-replacement.mp3")
        permittedResolution.set(PlayerLocalPlaybackResolution.Playable(replacement.path))

        assertUnavailableKeepsPending()

        assertPermittedResolutionOnIO()
    }

    @Test
    fun `permitted playback resolver failures retain restored progress`() {
        listOf(SecurityException("catalog grant unavailable"), IllegalStateException("catalog unavailable")).forEach { failure ->
            withFixture {
                permittedFailure.set(failure)

                assertUnavailableKeepsPending { scheduleValidation() }

                assertPermittedResolutionOnIO()
            }
        }
    }

    @Test
    fun `a newer request during managed resolution prevents stale service cleanup`() = withFixture {
        permittedResolution.set(PlayerLocalPlaybackResolution.Missing)
        val barrier = ProbeBarrier()
        beforeResolution = barrier::awaitRelease
        val pending = manager.restoredPlaybackSnapshot()
        val request = scheduleValidation()
        try {
            barrier.entered.await()
            manager.playbackRequestToken += 1
        } finally {
            barrier.release.countDown()
        }
        request.join()

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
    }

    @Test
    fun `bootstrap source validation skips remote media without consuming pending progress`() = withFixture {
        val pending = manager.restoredPlaybackSnapshot()
        val remote = song.copy(localFilePath = null, mediaUri = null)
        manager.publishCurrentQueue(listOf(remote), 0)
        manager._currentSongFlow.value = remote

        scheduleValidation().join()

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertTrue(providerThreads.isEmpty())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `bootstrap source validation skips absent paused and unselected restore states`() = withFixture {
        manager.clearRestoredPlayback()
        scheduleValidation().join()
        assertSame(RestoredPlaybackState.None, manager.restoredPlaybackSnapshot())
        manager.setRestoredPlayback(PositionMs, false)
        val paused = manager.restoredPlaybackSnapshot()
        scheduleValidation().join()
        assertSame(paused, manager.restoredPlaybackSnapshot())
        manager.setRestoredPlayback(PositionMs, true)
        val pending = manager.restoredPlaybackSnapshot()
        manager.publishCurrentQueue(emptyList(), -1)
        scheduleValidation().join()
        assertSame(pending, manager.restoredPlaybackSnapshot())

        assertTrue(providerThreads.isEmpty())
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    @Test
    fun `a newer playback token rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) { manager.playbackRequestToken += 1 }
    }

    @Test
    fun `a different selected index rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) {
            manager.publishCurrentQueue(listOf(song, song.copy(id = 552L)), 1)
        }
    }

    @Test
    fun `a replacement queued source rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) {
            manager.publishCurrentQueue(listOf(song.copy(localFilePath = "/new/source.mp3")), 0)
        }
    }

    @Test
    fun `a replacement current song rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) {
            manager._currentSongFlow.value = song.copy(localFilePath = "/new/source.mp3")
        }
    }

    @Test
    fun `an equal replacement pending state rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) {
            manager.setRestoredPlayback(PositionMs, true)
        }
    }

    @Test
    fun `pausing rejects a failed bootstrap validation without consuming the paused state`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) { manager.suppressRestoredAutoResume() }
        assertEquals(RestoredPlaybackState.Paused(PositionMs), manager.restoredPlaybackSnapshot())
    }

    @Test
    fun `releasing the player rejects a failed bootstrap validation`() = withFixture {
        assertRejectedAfterProbe(unavailable = true, validationOnly = true) { manager.initialized = false }
    }

    @Test
    fun `bootstrap source validation skips active rooms without consuming their pending restore`() {
        listOf("listener", "controller").forEach { role ->
            withFixture {
                session.value = ListenTogetherSessionState(roomId = "room", role = role)
                val pending = manager.restoredPlaybackSnapshot()

                scheduleValidation().join()

                assertSame(pending, manager.restoredPlaybackSnapshot())
                assertTrue(providerThreads.isEmpty())
                assertTrue(sourceUnavailableThreads.isEmpty())
                assertTrue(playbackThreads.isEmpty())
                assertTrue(persistedResumeIntent.isEmpty())
            }
        }
    }

    @Test
    fun `joining a room during bootstrap validation protects the service without consuming pending progress`() {
        listOf("listener", "controller").forEach { role ->
            withFixture {
                assertRejectedAfterProbe(unavailable = true, validationOnly = true) {
                    session.value = ListenTogetherSessionState(roomId = "room", role = role)
                }
            }
        }
    }

    @Test
    fun `cancelling bootstrap source validation cannot report an unavailable source`() = withFixture {
        readable.set(false)
        val barrier = ProbeBarrier()
        beforeOpen = barrier::awaitRelease
        val pending = manager.restoredPlaybackSnapshot()
        val request = scheduleValidation()
        try {
            barrier.entered.await()
            request.cancel()
        } finally {
            barrier.release.countDown()
        }
        request.cancelAndJoin()

        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertEquals(PositionMs, manager._playbackPositionMs.value)
        assertEquals(listOf(song), manager.currentQueueSnapshot().playlist)
        assertTrue(sourceUnavailableThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
        assertTrue(persistedResumeIntent.isEmpty())
    }

    private fun withFixture(block: suspend ResumeFixture.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val manager = PlayerManager
            val applicationField = PlayerManager::class.java.getDeclaredField("application").apply { isAccessible = true }
            val previousApplication = applicationField.get(null)
            val previousInitialized = manager.initialized
            val previousSong = manager._currentSongFlow.value
            val previousQueue = manager.currentQueueSnapshot()
            val previousRestore = manager.restoredPlaybackSnapshot()
            val previousPosition = manager._playbackPositionMs.value
            val previousToken = manager.playbackRequestToken
            val previousPersistTime = manager.lastStatePersistAtMs
            val registry = PlayerDependencies::class.java.getDeclaredField("registry").apply { isAccessible = true }.get(null)
            val environmentField = registry.javaClass.getDeclaredField("environment").apply { isAccessible = true }
            val previousEnvironment = environmentField.get(registry)
            val fixture = ResumeFixture(this)
            val playbackClass = Class.forName("moe.ouom.neriplayer.core.player.playback.PlayerManagerPlaybackExtensionsKt")
            val persistenceClass = Class.forName("moe.ouom.neriplayer.core.player.persistence.PlayerManagerStatePersistenceExtensionsKt")
            mockStatic(playbackClass).use { playback ->
                mockStatic(persistenceClass).use { persistence ->
                    playback.`when`<Unit> {
                        manager.playAtIndex(0, resumePositionMs = PositionMs, forceStartupProtectionFade = true)
                    }.thenAnswer {
                        fixture.playbackThreads += Looper.myLooper() == Looper.getMainLooper()
                        null
                    }
                    persistence.`when`<Unit> {
                        manager.scheduleStatePersist(PositionMs, false, 0L)
                    }.thenAnswer {
                        fixture.persistedResumeIntent += false
                        null
                    }
                    try {
                        environmentField.set(registry, null)
                        PlayerDependencies.install(fixture.environment())
                        manager.application = fixture.application
                        manager.initialized = true
                        manager.publishCurrentQueue(listOf(fixture.song), 0)
                        manager._currentSongFlow.value = fixture.song
                        manager._playbackPositionMs.value = PositionMs
                        manager.setRestoredPlayback(PositionMs, true)
                        fixture.block()
                    } finally {
                        manager.publishCurrentQueue(previousQueue.playlist, previousQueue.currentIndex)
                        manager._currentSongFlow.value = previousSong
                        manager._playbackPositionMs.value = previousPosition
                        manager.playbackRequestToken = previousToken
                        manager.lastStatePersistAtMs = previousPersistTime
                        manager.initialized = previousInitialized
                        applicationField.set(null, previousApplication)
                        environmentField.set(registry, previousEnvironment)
                        when (previousRestore) {
                            RestoredPlaybackState.None -> manager.clearRestoredPlayback()
                            is RestoredPlaybackState.Paused -> manager.setRestoredPlayback(previousRestore.positionMs, false)
                            is RestoredPlaybackState.ResumePending -> manager.setRestoredPlayback(previousRestore.positionMs, true)
                        }
                    }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun contentOpenFailures(): List<Exception?> = listOf(
        null, FileNotFoundException("document deleted"), SecurityException("document access denied"),
        IllegalStateException("provider unavailable")
    )

    private fun unavailablePermittedResolutions(): List<PlayerLocalPlaybackResolution> = listOf(
        PlayerLocalPlaybackResolution.NotIndexed, PlayerLocalPlaybackResolution.Missing,
        PlayerLocalPlaybackResolution.TemporarilyUnavailable("catalog warming up")
    )

    private data class LocalResolutionRequest(
        val song: SongItem,
        val reference: String?,
        val source: ResolvedLocalMediaSource?,
        val onMain: Boolean
    )

    private class ResumeFixture(private val scope: CoroutineScope) : CoroutineScope by scope {
        val manager = PlayerManager
        val application = mock(Application::class.java)
        val readable = AtomicBoolean(true)
        val permissionFailure = AtomicReference<RuntimeException?>()
        val openFailure = AtomicReference<Exception?>()
        val providerThreads = ConcurrentLinkedQueue<Boolean>()
        private val openProbeThreads = ConcurrentLinkedQueue<Boolean>()
        private val permission = mock(UriPermission::class.java)
        private val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
        private val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album/restore.mp3")
        val playbackThreads = mutableListOf<Boolean>()
        val persistedResumeIntent = mutableListOf<Boolean>()
        val sourceUnavailableThreads = mutableListOf<Boolean>()
        val permittedResolution = AtomicReference<PlayerLocalPlaybackResolution?>()
        val permittedFailure = AtomicReference<RuntimeException?>()
        val resolutionRequests = ConcurrentLinkedQueue<LocalResolutionRequest>()
        var beforeResolution: (() -> Unit)? = null
        var resumedCallbacks = 0
        val session = MutableStateFlow(ListenTogetherSessionState())
        var beforeOpen: (() -> Unit)? = null
        val song = SongItem(
            id = 551L, name = "Restored local source", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, localFilePath = "/storage/emulated/0/Music/Album/restore.mp3"
        )

        init {
            val resolver = mock(ContentResolver::class.java)
            doReturn(resolver).`when`(application).contentResolver
            doReturn(tree).`when`(permission).uri
            doReturn(true).`when`(permission).isReadPermission
            doAnswer {
                providerThreads += Looper.myLooper() == Looper.getMainLooper()
                permissionFailure.get()?.let { throw it }
                listOf(permission)
            }.`when`(resolver).persistedUriPermissions
            doAnswer {
                providerThreads += Looper.myLooper() == Looper.getMainLooper()
                openProbeThreads += Looper.myLooper() == Looper.getMainLooper()
                beforeOpen?.invoke()
                openFailure.get()?.let { throw it }
                if (readable.get()) mock(AssetFileDescriptor::class.java) else null
            }.`when`(resolver).openAssetFileDescriptor(document, "r")
        }

        fun selectGrantedContentSource() {
            doReturn(document).`when`(permission).uri
            val contentSong = song.copy(localFilePath = null, mediaUri = document.toString())
            manager.publishCurrentQueue(listOf(contentSong), 0)
            manager._currentSongFlow.value = contentSong
        }

        fun assertContentOpenedOnIO() {
            assertTrue("必须实际打开文档而非只检查授权", openProbeThreads.isNotEmpty())
            assertTrue(openProbeThreads.none { it })
        }

        fun assertPermittedResolutionOnIO() {
            assertTrue("必须经过受准入策略保护的本地播放解析", resolutionRequests.isNotEmpty())
            assertTrue(resolutionRequests.none { it.onMain })
        }

        fun environment(): PlayerEnvironment {
            val listenTogether = mock(PlayerListenTogetherAccess::class.java)
            doReturn(session).`when`(listenTogether).sessionState
            doReturn(MutableStateFlow<ListenTogetherRoomState?>(null)).`when`(listenTogether).roomState
            return PlayerEnvironment(
                application, mock(PlayerRepositoryDependencies::class.java), mock(PlayerDownloadAccess::class.java, Answer { invocation ->
                    if (invocation.method.name != "resolvePermittedLocalPlayback") {
                        Answers.RETURNS_DEFAULTS.answer(invocation)
                    } else {
                        val reference = invocation.getArgument<String?>(2)
                        resolutionRequests += LocalResolutionRequest(
                            invocation.getArgument(1), reference, invocation.getArgument(3),
                            Looper.myLooper() == Looper.getMainLooper()
                        )
                        beforeResolution?.invoke()
                        permittedFailure.get()?.let { throw it }
                        permittedResolution.get() ?: reference?.let(PlayerLocalPlaybackResolution::Playable)
                            ?: PlayerLocalPlaybackResolution.NotIndexed
                    }
                }),
                listenTogether, mock(PlayerPresentationHost::class.java), { true }, { Job().apply { complete() } }
            )
        }

        fun scheduleResume(): Job = manager.scheduleRestoredPlaybackResume(
            this,
            onSourceUnavailable = { sourceUnavailableThreads += Looper.myLooper() == Looper.getMainLooper() }
        ) { resumedCallbacks++ }

        fun scheduleValidation(scope: CoroutineScope = this): Job = manager.scheduleRestoredLocalSourceValidation(scope) {
            sourceUnavailableThreads += Looper.myLooper() == Looper.getMainLooper()
        }

        suspend fun assertUnavailableKeepsPending(schedule: () -> Job = { scheduleResume() }) {
            val pending = manager.restoredPlaybackSnapshot()
            val queue = manager.currentQueueSnapshot()
            val current = manager._currentSongFlow.value
            val position = manager._playbackPositionMs.value
            val persisted = persistedResumeIntent.toList()

            schedule().join()

            assertEquals(listOf(true), sourceUnavailableThreads)
            assertEquals(0, resumedCallbacks)
            assertSame(pending, manager.restoredPlaybackSnapshot())
            assertEquals(queue, manager.currentQueueSnapshot())
            assertEquals(current, manager._currentSongFlow.value)
            assertEquals(position, manager._playbackPositionMs.value)
            assertEquals(persisted, persistedResumeIntent)
            assertTrue(playbackThreads.isEmpty())
        }

        suspend fun assertRejectedAfterProbe(
            unavailable: Boolean = false,
            validationOnly: Boolean = false,
            mutate: () -> Unit
        ) {
            if (unavailable) readable.set(false)
            val barrier = ProbeBarrier()
            beforeOpen = barrier::awaitRelease
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                if (unavailable || validationOnly) {
                    if (validationOnly) scheduleValidation().join() else scheduleResume().join()
                    null
                } else manager.resumeRestoredPlaybackIfNeeded()
            }
            try {
                barrier.entered.await()
                mutate()
                val restoredAfterMutation = manager.restoredPlaybackSnapshot()
                val queueAfterMutation = manager.currentQueueSnapshot()
                val currentAfterMutation = manager._currentSongFlow.value
                val persistedAfterMutation = persistedResumeIntent.toList()
                barrier.release.countDown()
                assertNull(request.await())
                assertSame(restoredAfterMutation, manager.restoredPlaybackSnapshot())
                assertEquals(queueAfterMutation, manager.currentQueueSnapshot())
                assertEquals(currentAfterMutation, manager._currentSongFlow.value)
                assertEquals(persistedAfterMutation, persistedResumeIntent)
                assertTrue(playbackThreads.isEmpty())
                assertTrue(sourceUnavailableThreads.isEmpty())
                assertEquals(0, resumedCallbacks)
                assertEquals(PositionMs, manager._playbackPositionMs.value)
            } finally {
                barrier.release.countDown()
            }
        }
    }

    private class ProbeBarrier(private val expectedProbes: Int = 1) {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        private val count = AtomicInteger()

        fun awaitRelease() {
            if (count.incrementAndGet() == expectedProbes) entered.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS)) { "provider probe was not released" }
        }
    }

    private companion object {
        const val PositionMs = 12_345L
    }
}
