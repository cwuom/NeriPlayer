package moe.ouom.neriplayer.ui.screen.tab.home

import android.content.Context
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshots.Snapshot
import com.google.gson.Gson
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.playlist.usage.PlaylistUsageRepository
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class HomeUsageObserverTest {
    @get:Rule
    val files = TemporaryFolder()

    @After
    fun resetDispatcher() = Dispatchers.resetMain()

    @Test
    fun `cold start does not accept initial empty usage before nonempty storage finishes`() = runTest {
        withFixture(listOf(entry())) { fixture ->
            assertFalse(fixture.snapshot.isLoaded)
            assertTrue(fixture.continueVisible())

            fixture.finishRead()

            assertTrue(fixture.snapshot.isLoaded)
            assertEquals(listOf(entry()), fixture.snapshot.entries)
            assertTrue(fixture.continueVisible())
        }
    }

    @Test
    fun `trusted empty usage hides continue only after storage finishes`() = runTest {
        withFixture(emptyList()) { fixture ->
            assertFalse(fixture.snapshot.isLoaded)
            assertTrue(fixture.continueVisible())

            fixture.finishRead()

            assertTrue(fixture.snapshot.isLoaded)
            assertTrue(fixture.snapshot.entries.isEmpty())
            assertFalse(fixture.continueVisible())
            assertFalse(fixture.continueVisible(enabled = false))
        }
    }

    @Test
    fun `failed usage read keeps the observer untrusted`() = runTest {
        withFixture(listOf(entry()), IOException("fixture read failed")) { fixture ->
            fixture.finishRead()

            assertFalse(fixture.snapshot.isLoaded)
            assertTrue(fixture.continueVisible())
            assertTrue(fixture.snapshot.entries.isEmpty())
        }
    }

    @Test
    fun `cancelled usage read cannot publish loaded empty usage`() = runTest {
        withFixture(listOf(entry()), CancellationException("fixture read cancelled")) { fixture ->
            fixture.finishRead()

            assertFalse(fixture.snapshot.isLoaded)
            assertTrue(fixture.continueVisible())
            assertTrue(fixture.snapshot.entries.isEmpty())
        }
    }

    @Test
    fun `a loaded observer keeps following later repository changes`() = runTest {
        withFixture(listOf(entry())) { fixture ->
            fixture.finishRead()
            assertEquals(fixture.loadDescription(), listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
            assertEquals(fixture.loadDescription(), listOf(entry()), fixture.snapshot.entries)
            fixture.repository.updateInfo(1, "updated", null, 5, source = "netease", now = 300)
            fixture.awaitSnapshot { it.entries.singleOrNull()?.name == "updated" }

            assertTrue(fixture.snapshot.isLoaded)
            assertEquals(fixture.loadDescription(), 1, fixture.repository.frequentPlaylistsFlow.value.size)
            assertEquals("updated", fixture.snapshot.entries.single().name)
            assertEquals(5, fixture.snapshot.entries.single().trackCount)
            assertEquals(100L, fixture.snapshot.entries.single().lastOpened)
            assertEquals(2, fixture.snapshot.entries.single().openCount)
        }
    }

    @Test
    fun `a failed queued mutation cannot keep a successful initial read hidden`() = runTest {
        withFixture(listOf(entry())) { fixture ->
            val cancelled = CancellationException("queued deletion read cancelled")
            fixture.failDeletionRead(cancelled)
            fixture.repository.recordOpen(1, "queued", null, 3, source = "netease", now = 300)

            fixture.finishRead()

            assertSame(cancelled, fixture.initialCompletion)
            assertTrue(fixture.snapshot.isLoaded)
            assertEquals(listOf(entry()), fixture.snapshot.entries)
            assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
        }
    }

    private suspend fun TestScope.withFixture(
        stored: List<UsageEntry>,
        failure: Exception? = null,
        test: suspend (Fixture) -> Unit
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        files.root.resolve("playlist_usage.json").writeText(Gson().toJson(stored))
        val fixture = Fixture(this, failure)
        try {
            fixture.start()
            fixture.pump()
            test(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun entry() = UsageEntry(
        id = 1, name = "stored", picUrl = null, trackCount = 3,
        source = "netease", lastOpened = 100, openCount = 2
    )

    private inner class Fixture(private val scope: TestScope, private val failure: Exception?) {
        private val readStarted = CountDownLatch(1)
        private val releaseRead = CountDownLatch(1)
        private val context = mock(Context::class.java).also { context ->
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.filesDir).thenAnswer {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "fixture did not release storage read" }
                failure?.let { throw it }
                files.root
            }
        }
        val repository = PlaylistUsageRepository::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .also { it.isAccessible = true }
            .newInstance(context, null) as PlaylistUsageRepository
        private val repositoryScope = field("scope") as CoroutineScope
        private val initialLoad = field("initialLoad") as Job
        private val storage = mock(SecureTokenStorage::class.java)
        private val frameRequests = Channel<Unit>(Channel.UNLIMITED)
        private val snapshotChanges = Channel<HomeUsageSnapshot>(Channel.UNLIMITED)
        private val frameClock = BroadcastFrameClock { frameRequests.trySend(Unit) }
        private val recomposer = Recomposer(scope.coroutineContext + frameClock)
        private val composition = Composition(EmptyApplier(), recomposer)
        private val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        private var frameTime = 0L
        var snapshot = HomeUsageSnapshot()
            private set
        @Volatile
        var initialCompletion: Throwable? = null
            private set

        init {
            `when`(storage.getOrCreateDeviceId()).thenReturn("fixture-device")
            `when`(storage.getPlaylistUsageDeletions()).thenReturn(emptyMap())
            `when`(storage.getPlaylistUsageDeletionsConfirmed()).thenReturn(emptyMap())
            `when`(storage.getPlaylistUsageDeletionBarriersConfirmed()).thenReturn(emptyList())
            PlaylistUsageRepository::class.java.getDeclaredField("syncStorage\$delegate")
                .also { it.isAccessible = true }.set(repository, lazy { storage })
            initialLoad.invokeOnCompletion { initialCompletion = it }
            check(readStarted.await(5, TimeUnit.SECONDS)) { "fixture storage read did not start" }
        }

        fun failDeletionRead(error: Exception) {
            `when`(storage.getPlaylistUsageDeletionBarriersConfirmed()).thenAnswer { throw error }
        }

        fun start() {
            composition.setContent {
                val observed = rememberHomeUsageSnapshot(
                    key = repository,
                    readDispatcher = StandardTestDispatcher(scope.testScheduler)
                ) { repository }.value
                SideEffect {
                    snapshot = observed
                    snapshotChanges.trySend(observed)
                }
            }
        }

        fun continueVisible(enabled: Boolean = true) = shouldShowHomeContinueSection(
            showContinueCard = enabled,
            usageLoaded = snapshot.isLoaded,
            hasUsage = snapshot.entries.isNotEmpty()
        )

        fun loadDescription(): String = "initialized=${field("initialized")}, " +
            "initialFailure=${(field("initialLoadFailure") as? Exception)?.javaClass?.simpleName}, " +
            "rawCount=${repository.frequentPlaylistsFlow.value.size}"

        suspend fun finishRead() {
            releaseRead.countDown()
            initialLoad.join()
            pump()
            if (failure == null) {
                awaitSnapshot { it.isLoaded && it.entries == repository.frequentPlaylistsFlow.value }
            }
        }

        fun pump() {
            do {
                scope.runCurrent()
                Snapshot.sendApplyNotifications()
                scope.runCurrent()
                val frame = frameRequests.tryReceive().getOrNull()
                if (frame != null) frameClock.sendFrame(frameTime++)
            } while (frame != null)
            scope.runCurrent()
        }

        suspend fun awaitSnapshot(accepts: (HomeUsageSnapshot) -> Boolean) {
            val frames = scope.launch {
                while (frameRequests.receiveCatching().isSuccess) {
                    Snapshot.sendApplyNotifications()
                    frameClock.sendFrame(frameTime++)
                }
            }
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(2_000) {
                        do {
                            Snapshot.sendApplyNotifications()
                            frameRequests.trySend(Unit)
                            val observed = snapshotChanges.receive()
                        } while (!accepts(observed))
                    }
                }
            } finally {
                frames.cancelAndJoin()
            }
        }

        suspend fun close() {
            releaseRead.countDown()
            initialLoad.join()
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            recomposer.join()
            repositoryScope.coroutineContext[Job]?.cancelAndJoin()
            frameRequests.close()
            snapshotChanges.close()
        }

        private fun field(name: String) = PlaylistUsageRepository::class.java
            .getDeclaredField(name).also { it.isAccessible = true }.get(repository)
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
