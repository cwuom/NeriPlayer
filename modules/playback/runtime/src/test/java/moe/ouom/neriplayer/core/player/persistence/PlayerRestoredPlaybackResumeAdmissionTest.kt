package moe.ouom.neriplayer.core.player.persistence

import android.app.Application
import android.content.ContentResolver
import android.content.UriPermission
import android.content.res.AssetFileDescriptor
import android.os.Looper
import android.provider.DocumentsContract
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRestoredPlaybackResumeAdmissionTest {
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
        var resumed = 0
        manager.scheduleRestoredPlaybackResume(this) { resumed++ }.join()

        assertEquals(1, resumed)
        assertEquals(1, playbackThreads.size)
    }

    @Test
    fun `the service scheduling entry does not report an unreadable restore`() = withFixture {
        readable.set(false)
        var resumed = 0
        val pending = manager.restoredPlaybackSnapshot()

        manager.scheduleRestoredPlaybackResume(this) { resumed++ }.join()

        assertEquals(0, resumed)
        assertSame(pending, manager.restoredPlaybackSnapshot())
        assertTrue(playbackThreads.isEmpty())
    }

    @Test
    fun `absent paused and unselected queues do not probe the provider`() = withFixture {
        manager.clearRestoredPlayback()
        assertNull(manager.resumeRestoredPlaybackIfNeeded())
        manager.setRestoredPlayback(PositionMs, false)
        assertNull(manager.resumeRestoredPlaybackIfNeeded())
        manager.setRestoredPlayback(PositionMs, true)
        manager.publishCurrentQueue(emptyList(), -1)
        assertNull(manager.resumeRestoredPlaybackIfNeeded())

        assertTrue(providerThreads.isEmpty())
        assertTrue(playbackThreads.isEmpty())
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

    private class ResumeFixture(private val scope: CoroutineScope) : CoroutineScope by scope {
        val manager = PlayerManager
        val application = mock(Application::class.java)
        val readable = AtomicBoolean(true)
        val permissionFailure = AtomicReference<RuntimeException?>()
        val providerThreads = ConcurrentLinkedQueue<Boolean>()
        val playbackThreads = mutableListOf<Boolean>()
        val persistedResumeIntent = mutableListOf<Boolean>()
        val session = MutableStateFlow(ListenTogetherSessionState())
        var beforeOpen: (() -> Unit)? = null
        val song = SongItem(
            id = 551L, name = "Restored local source", artist = "Artist", album = "Album", albumId = 0L,
            durationMs = 180_000L, coverUrl = null, localFilePath = "/storage/emulated/0/Music/Album/restore.mp3"
        )

        init {
            val resolver = mock(ContentResolver::class.java)
            val permission = mock(UriPermission::class.java)
            val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Music")
            val document = DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Music/Album/restore.mp3")
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
                beforeOpen?.invoke()
                if (readable.get()) mock(AssetFileDescriptor::class.java) else null
            }.`when`(resolver).openAssetFileDescriptor(document, "r")
        }

        fun environment(): PlayerEnvironment {
            val listenTogether = mock(PlayerListenTogetherAccess::class.java)
            doReturn(session).`when`(listenTogether).sessionState
            doReturn(MutableStateFlow<ListenTogetherRoomState?>(null)).`when`(listenTogether).roomState
            return PlayerEnvironment(
                application, mock(PlayerRepositoryDependencies::class.java), mock(PlayerDownloadAccess::class.java),
                listenTogether, mock(PlayerPresentationHost::class.java), { true }, { Job().apply { complete() } }
            )
        }

        suspend fun assertRejectedAfterProbe(mutate: () -> Unit) {
            val barrier = ProbeBarrier()
            beforeOpen = barrier::awaitRelease
            val request = async(start = CoroutineStart.UNDISPATCHED) { manager.resumeRestoredPlaybackIfNeeded() }
            try {
                barrier.entered.await()
                mutate()
                val restoredAfterMutation = manager.restoredPlaybackSnapshot()
                val queueAfterMutation = manager.currentQueueSnapshot()
                barrier.release.countDown()
                assertNull(request.await())
                assertSame(restoredAfterMutation, manager.restoredPlaybackSnapshot())
                assertEquals(queueAfterMutation, manager.currentQueueSnapshot())
                assertTrue(playbackThreads.isEmpty())
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
