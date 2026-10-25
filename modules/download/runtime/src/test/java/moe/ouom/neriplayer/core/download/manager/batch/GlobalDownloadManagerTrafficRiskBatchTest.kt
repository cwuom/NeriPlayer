package moe.ouom.neriplayer.core.download.manager.batch

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomFixture
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.request
import moe.ouom.neriplayer.core.download.execution.persistence.testSong
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHostRegistry
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.core.download.manager.catalog.beginDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.catalog.endDownloadedSongDeletion
import moe.ouom.neriplayer.core.download.manager.recovery.restorePersistedBatchDownloadPresentations
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.traffic.TrafficNetworkType
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GlobalDownloadManagerTrafficRiskBatchTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val fixture = DownloadExecutionRoomFixture()
    private val manager = GlobalDownloadManager
    private val databaseInstance = NeriUserDataDatabase::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private val hostRegistry = DownloadHosts::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val installedHosts = DownloadHostRegistry::class.java.getDeclaredField("installed")
        .apply { isAccessible = true }
    private var previousDatabase: NeriUserDataDatabase? = null
    private var previousHosts: DownloadHostBindings? = null
    private var previousManagerJobs = emptySet<Job>()
    private var previousSnapshotJobs = emptySet<Job>()
    private var previousTaskAttempts = emptyMap<String, Long>()
    private lateinit var context: Context
    private val connectivity = mock(ConnectivityManager::class.java)
    private val network = mock(Network::class.java)
    private val capabilities = mock(NetworkCapabilities::class.java)
    private val sources = mock(DownloadSourceServices::class.java)

    @Before
    fun prepareFixture() {
        previousDatabase = databaseInstance.get(null) as NeriUserDataDatabase?
        previousHosts = installedHosts.get(hostRegistry) as DownloadHostBindings?
        previousManagerJobs = checkNotNull(manager.scope.coroutineContext[Job]).children.toSet()
        previousSnapshotJobs = checkNotNull(ManagedDownloadStorage.snapshotScope.coroutineContext[Job])
            .children.toSet()
        previousTaskAttempts = manager.taskStore.currentTasks().associate { it.song.stableKey() to it.attemptId }
        databaseInstance.set(null, fixture.database)
        `when`(connectivity.activeNetwork).thenReturn(network)
        `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
        `when`(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        val files = directory.newFolder("files")
        val external = directory.newFolder("external")
        context = object : ContextWrapper(fixture.context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
            override fun getExternalFilesDir(type: String?): File = external
            override fun getSystemService(name: String): Any? =
                if (name == CONNECTIVITY_SERVICE) connectivity else super.getSystemService(name)
        }
        DownloadHosts.install(DownloadHostBindings(
            environment = object : DownloadEnvironment {
                override val applicationContext: Context get() = context
                override val sharedOkHttpClient = OkHttpClient()
                override val instrumentationTestActive = true
                override fun recordDownloadBytes(networkType: TrafficNetworkType, bytes: Long) = Unit
                override suspend fun mobileDataHighRiskPromptEnabled(): Boolean = true
                override suspend fun awaitInteractiveContent() = Unit
                override fun scheduleQuarantineRecovery(context: Context) = Unit
            },
            sources = sources,
            lyrics = mock(DownloadLyricServices::class.java),
            credentials = mock(DownloadCredentials::class.java),
            playback = mock(DownloadedPlaybackHost::class.java)
        ))
    }

    @After
    fun closeFixture() = runBlocking {
        try {
            withTimeout(5_000L) {
                checkNotNull(manager.scope.coroutineContext[Job]).children
                    .filterNot { it in previousManagerJobs }.toList().forEach { it.cancelAndJoin() }
                checkNotNull(ManagedDownloadStorage.snapshotScope.coroutineContext[Job]).children
                    .filterNot { it in previousSnapshotJobs }.toList().forEach { it.cancelAndJoin() }
            }
            val createdTasks = manager.taskStore.currentTasks().filter { task ->
                previousTaskAttempts[task.song.stableKey()] != task.attemptId
            }.associate { it.song.stableKey() to it.attemptId }
            manager.taskStore.removeDownloadTasks(createdTasks)
            createdTasks.keys.forEach(manager.managedDownloadArtifactLeases::remove)
            AudioDownloadManager.clearNetworkPolicyPause(createdTasks.keys)
            manager.clearBatchDownloadPresentation()
        } finally {
            installedHosts.set(hostRegistry, previousHosts)
            databaseInstance.set(null, previousDatabase)
            fixture.close()
        }
    }

    @Test
    fun `unconfirmed mobile batch has no persistent work to restore`() =
        assertUnconfirmedBatchLeavesNoWork(TrafficNetworkType.MOBILE)

    @Test
    fun `unconfirmed roaming batch has no persistent work to restore`() =
        assertUnconfirmedBatchLeavesNoWork(TrafficNetworkType.ROAMING)

    @Test
    fun `repeated cancelled requests do not accumulate batches`() {
        repeat(3) { assertUnconfirmedBatchLeavesNoWork(TrafficNetworkType.MOBILE) }
    }

    @Test
    fun `confirmation persists one recoverable batch when the network becomes unavailable`() = runBlocking {
        useNetwork(TrafficNetworkType.MOBILE)
        val songs = listOf(testSong(91_001L), testSong(91_002L))
        val confirmation = awaitBatchConfirmation(songs)
        assertNoPersistedWork()
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
        assertEquals(null, context.currentDownloadNetworkTypeOrNull())

        withTimeout(10_000L) {
            val startup = checkNotNull(manager.startBatchDownload(
                context = context,
                songs = confirmation.songs,
                skipTrafficRiskPrompt = true,
                cleanupBeforeStart = false,
                deferForNetworkPolicy = true
            ))
            startup.join()
            assertFalse(startup.isCancelled)
            val batch = fixture.batchDao.findOpenBatches().single()
            val operations = fixture.operationDao.findAll()
            assertEquals(songs.size, batch.totalCount)
            assertEquals(songs.size, operations.size)
            assertEquals(songs.map(SongItem::stableKey).toSet(), operations.map { it.stableKey }.toSet())
            assertTrue(operations.all { it.state == "QUEUED" && it.batchId == batch.batchId })
            assertTrue(manager.taskStore.currentTasks().all { it.status == DownloadStatus.WAITING_NETWORK })
            verifyNoInteractions(sources)

            manager.clearBatchDownloadPresentation()
            manager.restorePersistedBatchDownloadPresentations(context)
            val restored = manager.batchDownloadPresentationsMutable.value.values.single()
            assertEquals(batch.batchId, restored.batchId)
            assertEquals(songs.size, restored.memberAttemptIds.size)
        }
    }

    @Test
    fun `wifi admission retains wifi policy when the network changes before staging`() = runBlocking {
        useNetwork(TrafficNetworkType.MOBILE)
        val wifi = mock(NetworkCapabilities::class.java)
        `when`(wifi.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).thenReturn(true)
        `when`(wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
        `when`(wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
        `when`(connectivity.getNetworkCapabilities(network)).thenAnswer {
            // 批次提交后切换网络，精确覆盖确认门与 operation 入队之间的窗口
            val batchExists = fixture.database.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM download_batch").use { cursor ->
                    cursor.moveToFirst() && cursor.getInt(0) > 0
                }
            if (batchExists) capabilities else wifi
        }
        assertEquals(TrafficNetworkType.WIFI, context.currentDownloadNetworkTypeOrNull())
        val songs = listOf(testSong(91_001L), testSong(91_002L))

        withTimeout(10_000L) {
            val startup = checkNotNull(manager.startBatchDownload(
                context = context,
                songs = songs,
                skipTrafficRiskPrompt = false,
                cleanupBeforeStart = false,
                deferForNetworkPolicy = true
            ))
            startup.join()
            assertFalse(startup.isCancelled)
            assertEquals(TrafficNetworkType.MOBILE, context.currentDownloadNetworkTypeOrNull())
            val operations = fixture.operationDao.findAll()
            assertEquals(songs.size, operations.size)
            operations.forEach { operation ->
                assertTrue("wifi admission must not authorize cellular transfer",
                    checkNotNull(DownloadExecutionRoomStore.read(context, operation.operationId, fixture.database))
                        .requiresWifiNetwork)
            }
            assertTrue(manager.taskStore.currentTasks().all { it.status == DownloadStatus.WAITING_NETWORK })
            verifyNoInteractions(sources)
        }
    }

    @Test
    fun `unconfirmed request does not promote or resume an existing owned operation`() = runBlocking {
        useNetwork(TrafficNetworkType.MOBILE)
        val song = testSong(91_001L)
        val identity = fixture.createBatch(listOf(song))
        val existing = request(song, userInitiated = false, batch = identity)
        fixture.upsert(existing, "QUEUED")
        DownloadExecutionRoomStore.attachBatchIdentity(context, identity, listOf(existing), fixture.database)
        val batchesBefore = fixture.batchDao.findOpenBatches()
        val membersBefore = fixture.batchDao.listMembers(identity.batchId)
        val operationsBefore = fixture.operationDao.findAll()

        assertEquals(listOf(song), awaitBatchConfirmation(listOf(song)).songs)

        assertEquals(batchesBefore, fixture.batchDao.findOpenBatches())
        assertEquals(membersBefore, fixture.batchDao.listMembers(identity.batchId))
        assertEquals(operationsBefore, fixture.operationDao.findAll())
        assertTrue(manager.taskStore.currentTasks().isEmpty())
        assertTrue(manager.batchDownloadPresentationsMutable.value.isEmpty())
        verifyNoInteractions(sources)
    }

    @Test
    fun `delete cleanup timeout requests confirmation before persisting deferred work`() = runBlocking {
        useNetwork(TrafficNetworkType.MOBILE)
        val songs = listOf(testSong(91_001L), testSong(91_002L))
        val keys = songs.map(SongItem::stableKey)
        manager.beginDownloadedSongDeletion(keys)
        try {
            val timeoutMs = GlobalDownloadManager.DOWNLOADED_SONG_DELETE_BARRIER_TIMEOUT_MS + 5_000L
            assertEquals(songs, awaitBatchConfirmation(songs, timeoutMs).songs)
            assertNoPersistedWork()
            verifyNoInteractions(sources)
        } finally {
            manager.endDownloadedSongDeletion(keys)
        }
    }

    private fun assertUnconfirmedBatchLeavesNoWork(networkType: TrafficNetworkType) = runBlocking {
        useNetwork(networkType)
        val songs = listOf(testSong(91_001L), testSong(91_002L))
        val request = awaitBatchConfirmation(songs)
        assertTrue(request.isBatch)
        assertEquals(networkType, request.networkType)
        assertEquals(songs, request.songs)
        assertNoPersistedWork()
    }

    private fun useNetwork(networkType: TrafficNetworkType) {
        `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING))
            .thenReturn(networkType == TrafficNetworkType.MOBILE)
        assertEquals(networkType, context.currentDownloadNetworkTypeOrNull())
    }

    private suspend fun awaitBatchConfirmation(
        songs: List<SongItem>,
        timeoutMs: Long = 10_000L
    ): GlobalDownloadManager.TrafficRiskDownloadRequest =
        withTimeout(timeoutMs) {
            val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
                manager.trafficRiskDownloadRequests.first()
            }
            val startup = checkNotNull(manager.startBatchDownload(
                context = context,
                songs = songs,
                skipTrafficRiskPrompt = false
            ))
            val request = confirmation.await()
            startup.join()
            assertFalse("batch startup must complete without cancellation", startup.isCancelled)
            request
        }

    private suspend fun assertNoPersistedWork() {
        // 取消确认框只丢弃请求，不能依靠额外清理撤销已经落盘的批次
        val persistedBatches = fixture.batchDao.findOpenBatches()
        val persistedOperations = fixture.operationDao.findAll()
        manager.restorePersistedBatchDownloadPresentations(context)

        assertTrue("unconfirmed batch must not be durable", persistedBatches.isEmpty())
        assertEquals("unconfirmed batch must not leave terminal rows either", null,
            fixture.batchDao.findMaxGeneration())
        assertTrue("unconfirmed batch must not create operations", persistedOperations.isEmpty())
        assertTrue("unconfirmed batch must not create tasks", manager.taskStore.currentTasks().isEmpty())
        assertTrue("restart must not restore an unconfirmed batch card",
            manager.batchDownloadPresentationsMutable.value.isEmpty())
    }
}
