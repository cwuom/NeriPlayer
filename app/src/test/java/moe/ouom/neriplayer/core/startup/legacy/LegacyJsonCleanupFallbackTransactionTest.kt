package moe.ouom.neriplayer.core.startup.legacy

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.store.RepositoryCutoverKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings

/**
 * Repositories commit their recovery JSON and the legacy_json marker in one Room transaction. The
 * cleanup must take the same writer lock and decide from the marker it reads there, not from the
 * plan it built earlier, or it deletes the JSON that just became the primary store.
 */
class LegacyJsonCleanupFallbackTransactionTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val writer = ReentrantLock()
    private val metadata = MetadataDao()
    private val database: NeriUserDataDatabase = mock(
        NeriUserDataDatabase::class.java,
        withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS)
    )

    init {
        doReturn(Executor { it.run() }).`when`(database).transactionExecutor
        @Suppress("DEPRECATION")
        run {
            doAnswer { writer.lock(); null }.`when`(database).beginTransaction()
            doAnswer { null }.`when`(database).setTransactionSuccessful()
            doAnswer { writer.unlock(); null }.`when`(database).endTransaction()
        }
        doReturn(metadata).`when`(database).syncMetadataDao()
    }

    @Test
    fun `cleanup waits for an in-flight JSON fallback and keeps the file it promoted`() {
        val filesDir = temporary.newFolder("files")
        val history = File(filesDir, "play_history.json").apply { writeText("[]") }
        metadata.put(RepositoryCutoverKeys.PLAY_HISTORY, LegacyJsonCleanupCoordinator.ROOM_PRIMARY_STATE)
        val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(filesDir) }
        val coordinator = LegacyJsonCleanupCoordinator(context, database)
        val stalePlan = runBlocking { coordinator.buildPlan() }
        assertTrue(stalePlan.existingEligibleTargets.any { it.fileName == history.name })

        val fallbackWritten = CountDownLatch(1)
        val fallback = thread(name = "play-history-fallback") {
            runBlocking {
                database.withTransaction {
                    history.writeText("""[{"id":7}]""")
                    fallbackWritten.countDown()
                    while (!writer.hasQueuedThreads()) Thread.sleep(1)
                    metadata.put(RepositoryCutoverKeys.PLAY_HISTORY, "legacy_json")
                }
            }
        }
        assertTrue(fallbackWritten.await(10, TimeUnit.SECONDS))

        val result = runBlocking { coordinator.execute(stalePlan, confirmed = true) }
        fallback.join(TimeUnit.SECONDS.toMillis(10))

        assertEquals("""[{"id":7}]""", history.readText())
        assertEquals(LegacyJsonCleanupStatus.BLOCKED, result.status)
        assertEquals(listOf(history.name), result.blockedFiles)
        assertEquals(emptyList<String>(), result.deletedFiles)
    }

    private class MetadataDao : SyncMetadataDao by mock(SyncMetadataDao::class.java) {
        private val rows = ConcurrentHashMap<String, MigrationMetadataEntity>()

        fun put(key: String, value: String) {
            rows[key] = MigrationMetadataEntity(key = key, value = value, updatedAt = 0L)
        }

        override suspend fun getMigrationMetadata(key: String): MigrationMetadataEntity? = rows[key]

        override suspend fun upsertMigrationMetadata(metadata: MigrationMetadataEntity) {
            rows[metadata.key] = metadata
        }
    }
}
