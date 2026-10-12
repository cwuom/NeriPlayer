@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.io.Closeable
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore
import moe.ouom.neriplayer.data.local.database.store.mockPlaylistUsageRoomStore
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

class PlaylistUsageReadinessRaceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `readiness queued behind persistence confirms newer home metadata instead of rejecting its generation`() = runTest {
        val primary = primary(listOf(localUsage()))
        Fixture(this, primary.room).use { fixture ->
            val lockRequested = CompletableDeferred<Unit>()
            val mutex = Mutex()
            val observedMutex = object : Mutex by mutex {
                override suspend fun lock(owner: Any?) {
                    lockRequested.complete(Unit)
                    mutex.lock(owner)
                }
            }
            PlaylistUsageRepository::class.java.getDeclaredField("persistenceMutex")
                .also { it.isAccessible = true }.set(fixture.repository, observedMutex)
            mutex.lock()
            val readiness = async(Dispatchers.Default) { fixture.repository.awaitInitialized() }
            try {
                withContext(Dispatchers.Default) { withTimeout(2_000) { lockRequested.await() } }
                fixture.repository.syncLocalEntries(
                    playlists = listOf(LocalPlaylist(
                        id = 7,
                        name = "refreshed home card",
                        songs = mutableListOf(SongItem(9, "song", "artist", "album", 0, 100, null))
                    )),
                    resolveLocalMetadataFallback = false
                )
                assertEquals("refreshed home card", fixture.repository.frequentPlaylistsFlow.value.single().name)
                assertEquals(listOf(localUsage()), primary.rows)
            } finally {
                mutex.unlock()
            }

            assertTrue(withContext(Dispatchers.Default) { withTimeout(2_000) { readiness.await() } })
            val expected = listOf(localUsage().copy(name = "refreshed home card", trackCount = 1))
            assertEquals(expected, primary.rows)
            assertEquals(expected, fixture.repository.frequentPlaylistsFlow.value)
            runCurrent()
            assertEquals("A queued older save must keep the confirmed latest snapshot", expected, primary.rows)
        }
    }

    @Test
    fun `cancelled initial primary read cannot establish empty readiness and explicit recovery loads stored usage`() = runTest {
        val primary = primary(listOf(localUsage()))
        var firstRead = true
        `when`(primary.room.readIfRoomPrimary()).thenAnswer {
            if (firstRead) {
                firstRead = false
                throw CancellationException("initial usage read cancelled")
            }
            primary.rows
        }
        Fixture(this, primary.room).use { fixture ->
            assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
            assertFalse(fixture.isInitialized())
            assertEquals(listOf(localUsage()), primary.rows)

            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(localUsage()), fixture.repository.frequentPlaylistsFlow.value)
            assertEquals(listOf(localUsage()), primary.rows)
            assertEquals(3, fixture.repository.syncStats().single().openCount)
        }
    }

    @Test
    fun `loaded usage subscription recovers after repeated read failures without writes or persistence generation changes`() = runTest {
        val primary = primary(listOf(localUsage()))
        val reads = AtomicInteger()
        `when`(primary.room.readIfRoomPrimary()).thenAnswer {
            if (reads.incrementAndGet() <= 3) throw IOException("primary temporarily unavailable")
            primary.rows
        }
        Fixture(this, primary.room).use { fixture ->
            val emissions = mutableListOf<List<UsageEntry>>()
            val observer = backgroundScope.launch { fixture.repository.loadedFrequentPlaylistsFlow.collect { emissions += it } }
            runCurrent()
            assertTrue("untrusted empty usage must not be published", emissions.isEmpty())

            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(listOf(listOf(localUsage())), emissions)
            assertEquals(listOf(localUsage()), primary.rows)
            assertEquals(0L, fixture.persistenceGeneration())
            assertFalse(temporary.root.resolve("playlist_usage.json").exists())
            observer.cancelAndJoin()
        }
    }

    @Test
    fun `concurrent usage subscriptions share an in flight retry and cancellation stops further reads`() = runTest {
        val primary = primary(listOf(localUsage()))
        val reads = AtomicInteger()
        var suspendedRead: Continuation<*>? = null
        doAnswer { invocation ->
            if (reads.incrementAndGet() == 1) throw IOException("initial primary read failed")
            check(suspendedRead == null) { "usage retries must not read concurrently" }
            suspendedRead = invocation.rawArguments.last() as Continuation<*>
            COROUTINE_SUSPENDED
        }.`when`(primary.room).readIfRoomPrimary()
        Fixture(this, primary.room).use { fixture ->
            val emissions = mutableListOf<List<UsageEntry>>()
            val first = backgroundScope.launch { fixture.repository.loadedFrequentPlaylistsFlow.collect { emissions += it } }
            val second = backgroundScope.launch { fixture.repository.loadedFrequentPlaylistsFlow.collect { emissions += it } }
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("both subscriptions must share one retry", 2, reads.get())
            assertTrue(emissions.isEmpty())
            first.cancelAndJoin()
            second.cancelAndJoin()
            requireNotNull(suspendedRead).resumeWith(Result.failure(IOException("retry still unavailable")))
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals("no subscribed observer remains to request another read", 2, reads.get())
            assertEquals(listOf(localUsage()), primary.rows)
            assertEquals(0L, fixture.persistenceGeneration())
        }
    }

    @Test
    fun `subscription recovery replays an early open once and preserves stored counters`() = runTest {
        val primary = primary(listOf(localUsage()))
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val reads = AtomicInteger()
        `when`(primary.room.readIfRoomPrimary()).thenAnswer {
            if (reads.incrementAndGet() == 1) {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "fixture did not release initial read" }
                throw IOException("initial primary read failed")
            }
            primary.rows
        }
        Fixture(this, primary.room) { repository ->
            try {
                check(readStarted.await(5, TimeUnit.SECONDS)) { "fixture initial read did not start" }
                repository.recordOpen(7, "queued open", null, 4, source = PlaylistUsageRepository.SOURCE_LOCAL, now = 300)
            } finally {
                releaseRead.countDown()
            }
        }.use { fixture ->
            val emissions = mutableListOf<List<UsageEntry>>()
            val observer = backgroundScope.launch { fixture.repository.loadedFrequentPlaylistsFlow.collect { emissions += it } }
            advanceTimeBy(60_000)
            runCurrent()

            val recovered = emissions.single().single()
            assertEquals(7L, recovered.id)
            assertEquals("queued open", recovered.name)
            assertEquals(4, recovered.openCount)
            assertEquals(300L, recovered.lastOpened)
            assertEquals(3L, recovered.counterBaseOpenCount)
            assertEquals(1, recovered.counterShards.single().playCount)
            assertEquals(listOf(recovered), primary.rows)
            assertEquals(listOf(recovered), fixture.repository.frequentPlaylistsFlow.value)
            observer.cancelAndJoin()
        }
    }

    private class UsagePrimary(initial: List<UsageEntry>) {
        val room = mockPlaylistUsageRoomStore()
        @Volatile var rows = initial.toList()
    }

    private suspend fun primary(initial: List<UsageEntry>): UsagePrimary {
        val primary = UsagePrimary(initial)
        `when`(primary.room.readIfRoomPrimary()).thenAnswer { primary.rows }
        doAnswer { invocation ->
            assertEquals(primary.rows, invocation.getArgument<List<UsageEntry>>(0))
            primary.rows = invocation.getArgument<List<UsageEntry>>(1).toList()
            Unit
        }.`when`(primary.room).writeIncremental(anyList(), anyList(), anyLong())
        return primary
    }

    private inner class Fixture(
        testScope: TestScope,
        room: PlaylistUsageRoomStore,
        beforeInitialCompletion: (PlaylistUsageRepository) -> Unit = {}
    ) : Closeable {
        private val ownedScope = CoroutineScope(
            SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScope.testScheduler)
        )
        val repository = PlaylistUsageRepository(localizedContext(), room)

        init {
            beforeInitialCompletion(repository)
            runBlocking { repository.awaitInitialLoad() }
            val scope = PlaylistUsageRepository::class.java.getDeclaredField("scope").also { it.isAccessible = true }
            (scope.get(repository) as CoroutineScope).cancel()
            scope.set(repository, ownedScope)
            val storage = mock(SecureTokenStorage::class.java)
            `when`(storage.getOrCreateDeviceId()).thenReturn("fixture-device")
            `when`(storage.getPlaylistUsageDeletions()).thenReturn(emptyMap())
            `when`(storage.getPlaylistUsageDeletionsConfirmed()).thenReturn(emptyMap())
            `when`(storage.getPlaylistUsageDeletionBarriersConfirmed()).thenReturn(emptyList())
            PlaylistUsageRepository::class.java.getDeclaredField("syncStorage\$delegate")
                .also { it.isAccessible = true }.set(repository, lazy { storage })
        }

        fun isInitialized(): Boolean = PlaylistUsageRepository::class.java.getDeclaredField("initialized")
            .also { it.isAccessible = true }.getBoolean(repository)

        fun persistenceGeneration(): Long = PlaylistUsageRepository::class.java.getDeclaredField("persistenceGeneration")
            .also { it.isAccessible = true }.getLong(repository)

        override fun close() = ownedScope.cancel()
    }

    private fun localizedContext(): Context {
        val resources = mock(Resources::class.java)
        val configuration = mock(Configuration::class.java)
        val locales = mock(LocaleList::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(resources.configuration).thenReturn(configuration)
        `when`(configuration.locales).thenReturn(locales)
        `when`(locales.size()).thenReturn(1)
        `when`(locales[0]).thenReturn(Locale.CHINA)
        return mock(Context::class.java).apply {
            `when`(applicationContext).thenReturn(this)
            `when`(filesDir).thenReturn(temporary.root)
            `when`(getSharedPreferences("language_settings", Context.MODE_PRIVATE)).thenReturn(preferences)
            `when`(preferences.getString("selected_language", "")).thenReturn("")
            `when`(createConfigurationContext(any(Configuration::class.java))).thenReturn(this)
            `when`(this.resources).thenReturn(resources)
        }
    }

    private fun localUsage() = UsageEntry(
        id = 7,
        name = "stored home card",
        picUrl = null,
        trackCount = 4,
        source = PlaylistUsageRepository.SOURCE_LOCAL,
        lastOpened = 100,
        openCount = 3
    )
}
