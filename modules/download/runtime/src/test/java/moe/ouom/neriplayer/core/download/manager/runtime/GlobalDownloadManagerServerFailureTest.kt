package moe.ouom.neriplayer.core.download.manager.runtime

import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomFixture
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.PostCoreRecoveryReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.request
import moe.ouom.neriplayer.core.download.manager.commit.settlePostCoreEnrichmentFailure
import moe.ouom.neriplayer.core.download.host.DownloadCredentials
import moe.ouom.neriplayer.core.download.host.DownloadEnvironment
import moe.ouom.neriplayer.core.download.host.DownloadHostBindings
import moe.ouom.neriplayer.core.download.host.DownloadHostRegistry
import moe.ouom.neriplayer.core.download.host.DownloadHosts
import moe.ouom.neriplayer.core.download.host.DownloadLyricServices
import moe.ouom.neriplayer.core.download.host.DownloadSourceServices
import moe.ouom.neriplayer.core.download.host.DownloadedPlaybackHost
import moe.ouom.neriplayer.core.player.download.source.RetryableDownloadFailureException
import moe.ouom.neriplayer.core.player.download.cover.AudioDownloadCoverCoordinator
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.server.ServerSongRef
import moe.ouom.neriplayer.platform.subsonic.api.SubsonicException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GlobalDownloadManagerServerFailureTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val manager = GlobalDownloadManager
    private val databaseField = NeriUserDataDatabase::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }
    private val registry = DownloadHosts::class.java.getDeclaredField("registry")
        .apply { isAccessible = true }.get(null)
    private val installed = DownloadHostRegistry::class.java.getDeclaredField("installed")
        .apply { isAccessible = true }
    private var previousDatabase: NeriUserDataDatabase? = null
    private var previousHosts: DownloadHostBindings? = null
    private var previousJobs = emptySet<Job>()
    private val song = ServerSongRef("00000000-0000-0000-0000-000000000019", "manager-failure").let { ref ->
        SongItem(ref.numericId, "Server", "Artist", "Album", 0L, 1000L, null,
            channelId = ServerSongRef.CHANNEL, audioId = ref.audioId, mediaUri = ref.mediaUri)
    }

    @Before
    fun installFixture() {
        previousDatabase = databaseField.get(null) as NeriUserDataDatabase?
        previousHosts = installed.get(registry) as DownloadHostBindings?
        previousJobs = checkNotNull(manager.scope.coroutineContext[Job]).children.toSet()
        databaseField.set(null, fixture.database)
        val environment = mock(DownloadEnvironment::class.java)
        `when`(environment.applicationContext).thenReturn(fixture.context)
        `when`(environment.instrumentationTestActive).thenReturn(true)
        DownloadHosts.install(DownloadHostBindings(environment, mock(DownloadSourceServices::class.java),
            mock(DownloadLyricServices::class.java), mock(DownloadCredentials::class.java),
            mock(DownloadedPlaybackHost::class.java)))
        manager.taskStore.ensureDownloadTasks(listOf(song))
        manager.requestGenerationTracker.begin(listOf(song))
    }

    @After
    fun restoreFixture() = runBlocking<Unit> {
        try {
            checkNotNull(manager.scope.coroutineContext[Job]).children.filterNot { it in previousJobs }
                .toList().forEach { it.cancelAndJoin() }
            manager.taskStore.findTask(song.stableKey())?.let { task ->
                manager.taskStore.removeDownloadTasks(mapOf(song.stableKey() to task.attemptId))
            }
        } finally {
            installed.set(registry, previousHosts)
            databaseField.set(null, previousDatabase)
            fixture.close()
        }
    }

    @Test
    fun `manager terminates server authentication failures without retrying`() = permanentFailure(SubsonicException.http(401, null))

    @Test
    fun `manager terminates missing server songs without retrying`() = permanentFailure(SubsonicException.http(404, null))

    @Test
    fun `manager terminates removed server profiles without retrying`() = permanentFailure(SubsonicException.accountUnavailable())

    private fun permanentFailure(error: SubsonicException) = runBlocking<Unit> {
        val attempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        fixture.upsert(request(song, operationId = "server-failure", attemptId = attempt), "RUNNING")

        manager.deferRetryableDownloadFailure(fixture.context, song, "server-failure", attempt, null,
            RetryableDownloadFailureException("safe failure", false, cause = error, errorCode = "DOWNLOAD_FAILED"))

        assertEquals("INVALID", fixture.row("server-failure").state)
        assertEquals(0, fixture.row("server-failure").retryCount)
        assertEquals(DownloadStatus.FAILED, manager.taskStore.findTask(song.stableKey())?.status)
    }

    @Test
    fun `stale server failure does not invalidate the newer running attempt`() = runBlocking<Unit> {
        val oldAttempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        val newAttempt = oldAttempt + 1L
        fixture.upsert(request(song, operationId = "server-new-running", attemptId = newAttempt), "RUNNING")

        manager.deferRetryableDownloadFailure(fixture.context, song, "server-new-running", oldAttempt, null,
            RetryableDownloadFailureException("safe stale failure", false, cause = SubsonicException.http(401, null)))

        assertEquals("RUNNING", fixture.row("server-new-running").state)
        assertEquals(newAttempt, fixture.read("server-new-running")?.attemptId)
        assertEquals(0, fixture.row("server-new-running").retryCount)
        assertEquals(DownloadStatus.QUEUED, manager.taskStore.findTask(song.stableKey())?.status)
    }

    @Test
    fun `stale server failure does not delete the newer pending attempt`() = runBlocking<Unit> {
        val oldAttempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        val newAttempt = oldAttempt + 1L
        fixture.upsert(request(song, operationId = "server-new-pending", attemptId = newAttempt), "QUEUED")
        assertEquals("server-new-pending", ManagedDownloadStorage.findQueuedOperationIdForSong(fixture.context, song.stableKey()))

        manager.deferRetryableDownloadFailure(fixture.context, song, "server-new-pending", oldAttempt, null,
            RetryableDownloadFailureException("safe stale failure", false, cause = SubsonicException.http(401, null)))

        assertEquals("server-new-pending", ManagedDownloadStorage.findQueuedOperationIdForSong(fixture.context, song.stableKey()))
        assertEquals("QUEUED", fixture.row("server-new-pending").state)
        assertEquals(newAttempt, fixture.read("server-new-pending")?.attemptId)
        assertEquals(0, fixture.row("server-new-pending").retryCount)
        assertEquals(DownloadStatus.QUEUED, manager.taskStore.findTask(song.stableKey())?.status)
    }

    @Test
    fun `manager preserves committed audio state after permanent server errors`() = runBlocking<Unit> {
        val attempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        fixture.upsert(request(song, operationId = "server-core", attemptId = attempt), "CORE_COMMITTED")

        manager.deferRetryableDownloadFailure(fixture.context, song, "server-core", attempt, null,
            RetryableDownloadFailureException("safe failure", false, cause = SubsonicException.http(401, null)))

        assertEquals("CORE_COMMITTED", fixture.row("server-core").state)
        assertEquals(0, fixture.row("server-core").retryCount)
    }

    @Test
    fun `server backoff survives network recovery even when public internet is unvalidated`() = runBlocking<Unit> {
        val attempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        fixture.upsert(request(song, operationId = "server-backoff", attemptId = attempt), "RUNNING")
        val error = SubsonicException.http(429, "120")
        manager.deferRetryableDownloadFailure(fixture.context, song, "server-backoff", attempt, null,
            RetryableDownloadFailureException("safe backoff", true, cause = error, retryAfterMs = error.retryAfterMs))
        val row = fixture.row("server-backoff")
        assertEquals("DOWNLOAD_SERVER_RETRY_AFTER", row.lastErrorCode)
        val deadline = checkNotNull(row.nextRetryAtMs)
        assertTrue(deadline > System.currentTimeMillis() + 110_000L)

        assertEquals(0, fixture.operationDao.clearRetryDeadlinesForImmediateRecovery(System.currentTimeMillis()))
        assertEquals(deadline, fixture.row("server-backoff").nextRetryAtMs)
        val waiting = DownloadExecutionRoomReadStore.listSchedulableForPumpPage(fixture.context, null,
            limit = 10, database = fixture.database, nowMs = deadline - 1L)
        assertTrue(waiting.requests.isEmpty())
        val resumed = DownloadExecutionRoomReadStore.listSchedulableForPumpPage(fixture.context, null,
            limit = 10, database = fixture.database, nowMs = deadline)
        assertEquals(listOf("server-backoff"), resumed.requests.map { it.operationId })
    }

    @Test
    fun `post core server retry after persists without charging worker settlement twice`() = runBlocking<Unit> {
        val attempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        val operationId = "server-post-core-backoff"
        fixture.upsert(request(song, operationId = operationId, attemptId = attempt), "DEGRADED_COMPLETE")

        manager.settlePostCoreEnrichmentFailure(fixture.context, song, operationId, attempt,
            "ASSET_ENRICHMENT_FAILED", SubsonicException.http(429, "120"), scheduleRetry = false)

        val row = fixture.row(operationId)
        assertEquals("DEGRADED_COMPLETE", row.state)
        assertEquals("DOWNLOAD_SERVER_RETRY_AFTER", row.lastErrorCode)
        assertEquals(1, row.retryCount)
        val deadline = checkNotNull(row.nextRetryAtMs)
        assertTrue(deadline > System.currentTimeMillis() + 110_000L)
        val observed = DownloadExecutionRoomStore.recordPostCoreRetryFailure(fixture.context, operationId,
            song.stableKey(), attempt, checkNotNull(row.lastErrorCode), fixture.database, nowMs = deadline - 1L)
        assertEquals(1, observed?.retryCount)
        assertEquals(deadline, observed?.nextRetryAtMs)
        assertEquals(1, fixture.row(operationId).retryCount)
        assertEquals(0, fixture.operationDao.clearRetryDeadlinesForImmediateRecovery(System.currentTimeMillis()))
        assertEquals(0, fixture.operationDao.rearmRetryableOperationsAfterProcessRestart("new-process", System.currentTimeMillis()))
        assertEquals(deadline, fixture.row(operationId).nextRetryAtMs)
        assertTrue(PostCoreRecoveryReadStore.select(fixture.database, 1, emptySet(), true,
            isExecuting = { false }, nowMs = deadline - 1L).isEmpty())
        assertEquals(listOf(operationId), PostCoreRecoveryReadStore.select(fixture.database, 1, emptySet(), true,
            isExecuting = { false }, nowMs = deadline).map { it.operationId })

        manager.settlePostCoreEnrichmentFailure(fixture.context, song, operationId, attempt,
            "ASSET_ENRICHMENT_FAILED", SubsonicException.http(429, "120"), scheduleRetry = false)
        assertEquals(2, fixture.row(operationId).retryCount)
    }

    @Test
    fun `post core server backoff from an old attempt cannot delay its replacement`() = runBlocking<Unit> {
        val oldAttempt = checkNotNull(manager.taskStore.findTask(song.stableKey())).attemptId
        fixture.upsert(request(song, operationId = "server-post-core-new", attemptId = oldAttempt + 1L), "DEGRADED_COMPLETE")

        manager.settlePostCoreEnrichmentFailure(fixture.context, song, "server-post-core-new", oldAttempt,
            "ASSET_ENRICHMENT_FAILED", SubsonicException.http(429, "120"), scheduleRetry = false)

        val row = fixture.row("server-post-core-new")
        assertEquals("DEGRADED_COMPLETE", row.state)
        assertEquals(0, row.retryCount)
        assertEquals(null, row.nextRetryAtMs)
    }

    @Test
    fun `server cover rate limit and authentication errors retain their typed identity without local retries`() = runBlocking<Unit> {
        listOf(SubsonicException.http(429, "120"), SubsonicException.http(401, null)).forEach { error ->
            var requests = 0
            val coordinator = AudioDownloadCoverCoordinator(3, 0L, 1024L,
                ensureNotCancelled = { _, _, _, _, _, _ -> },
                executeTrackedCall = { _, _, _, _, _ -> requests += 1; throw error },
                commitCover = { _, _, _, _, _, _, _, _, _ -> throw AssertionError("cover must not be committed") },
                rememberPartial = { _, _, _ -> })
            val coverSong = song.copy(coverUrl = "https://example.test/server-cover.png")
            val failure = runCatching {
                coordinator.cacheCover(fixture.context, coverSong, coverSong.stableKey(), "server-cover",
                    ManagedDownloadStorage.StoredEntry("server-cover.flac", "missing-audio", "", null, 1L, 1L),
                    allowIndexedLookup = false, operationId = "server-cover")
            }.exceptionOrNull()
            assertSame(error, failure)
            assertEquals(1, requests)
        }
    }

    @Test
    fun `ordinary cover transport failures keep their bounded local retries`() = runBlocking<Unit> {
        var requests = 0
        val coordinator = AudioDownloadCoverCoordinator(3, 0L, 1024L,
            ensureNotCancelled = { _, _, _, _, _, _ -> },
            executeTrackedCall = { _, _, _, _, _ -> requests += 1; throw java.io.IOException("temporary failure") },
            commitCover = { _, _, _, _, _, _, _, _, _ -> throw AssertionError("cover must not be committed") },
            rememberPartial = { _, _, _ -> })
        val ordinary = SongItem(19L, "ordinary", "artist", "album", 0L, 1000L, "https://example.test/cover.png")
        assertNull(coordinator.cacheCover(fixture.context, ordinary, ordinary.stableKey(), "ordinary-cover",
            ManagedDownloadStorage.StoredEntry("ordinary.flac", "missing-ordinary-audio", "", null, 1L, 1L),
            allowIndexedLookup = false, operationId = "ordinary-cover"))
        assertEquals(3, requests)
    }
}
