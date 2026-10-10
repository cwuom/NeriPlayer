package moe.ouom.neriplayer.data.local.database.store

import java.util.concurrent.Executor
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.SyncMetadataDao
import moe.ouom.neriplayer.data.local.database.entity.MigrationMetadataEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncOutboxEntity
import moe.ouom.neriplayer.data.local.database.entity.SyncReplicaCheckpointEntity
import org.junit.Assert.assertTrue
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.withSettings

/**
 * Room database double whose suspending transactions run inline on the calling thread.
 *
 * Room's `withTransaction` needs a real transaction executor and the thread-local transaction
 * context created by the [androidx.room.RoomDatabase] constructor, so the mock is constructed for
 * real and only the SQLite-facing transaction hooks are replaced. The log records every hook call,
 * which lets tests tell committed work apart from rolled-back work.
 */
internal class InlineTransactionDatabase {
    val transactionLog = mutableListOf<String>()
    val metadata = InMemorySyncMetadataDao()
    val database: NeriUserDataDatabase = mock(
        NeriUserDataDatabase::class.java,
        withSettings().useConstructor().defaultAnswer(CALLS_REAL_METHODS)
    )

    init {
        doReturn(Executor { it.run() }).`when`(database).transactionExecutor
        @Suppress("DEPRECATION")
        run {
            doAnswer { transactionLog += "begin"; null }.`when`(database).beginTransaction()
            doAnswer { transactionLog += "commit"; null }.`when`(database).setTransactionSuccessful()
            doAnswer { transactionLog += "end"; null }.`when`(database).endTransaction()
        }
        doReturn(metadata).`when`(database).syncMetadataDao()
    }
}

/** Migration metadata table kept in memory; the outbox and checkpoint tables are out of scope. */
internal class InMemorySyncMetadataDao : SyncMetadataDao {
    val rows = linkedMapOf<String, MigrationMetadataEntity>()

    fun put(key: String, value: String?) {
        rows[key] = MigrationMetadataEntity(key, value, 0L)
    }

    fun value(key: String): String? = rows[key]?.value

    override suspend fun upsertMigrationMetadata(metadata: MigrationMetadataEntity) {
        rows[metadata.key] = metadata
    }

    override suspend fun getMigrationMetadata(key: String): MigrationMetadataEntity? = rows[key]

    override suspend fun deleteMigrationMetadata(keys: List<String>) {
        keys.forEach(rows::remove)
    }

    override suspend fun insertOutbox(entry: SyncOutboxEntity): Long = unsupported()

    override suspend fun getOutbox(statuses: List<String>, limit: Int): List<SyncOutboxEntity> = unsupported()

    override suspend fun getOutboxPage(
        statuses: List<String>,
        afterSequence: Long,
        limit: Int
    ): List<SyncOutboxEntity> = unsupported()

    override suspend fun deleteOutboxByStatus(status: String): Unit = unsupported()

    override suspend fun updateOutbox(
        sequence: Long,
        status: String,
        attemptCount: Int,
        lastErrorType: String?,
        updatedAt: Long
    ): Unit = unsupported()

    override suspend fun upsertCheckpoint(checkpoint: SyncReplicaCheckpointEntity): Unit = unsupported()

    override suspend fun getCheckpoint(transportId: String): SyncReplicaCheckpointEntity? = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Room store tests only exercise migration metadata")
}

/** Names of the methods invoked on [mock], in call order. */
internal fun invokedMethods(mock: Any): List<String> =
    mockingDetails(mock).invocations.map { it.method.name }

/** Runs [block] and returns the failure it raised, asserting that it has the expected type. */
internal inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
    val failure = runCatching(block).exceptionOrNull()
    assertTrue("Expected ${T::class.java.simpleName} but got $failure", failure is T)
    return failure as T
}
