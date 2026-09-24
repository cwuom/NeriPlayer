package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.manager.runtime.PostCoreDownloadRecoveryResult
import moe.ouom.neriplayer.core.download.manager.runtime.recoverPostCoreDownloadsForWorkerImpl
import moe.ouom.neriplayer.data.traffic.currentDownloadNetworkTypeOrNull
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomReadStore
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.execution.persistence.PostCoreRecoveryReadStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PostCoreRecoveryReadStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun executingFutureBarrierIsSkippedAndEqualQueueTimesUseOperationId() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java).build()
        try {
            val dao = db.downloadOperationDao()
            DownloadExecutionRoomStore.upsert(context, DownloadExecutionRequest(
                operationId = "seed", song = SongItem(1, "test", "artist", "netease", 0, 1000, null),
                requiresWifiNetwork = false
            ), "CORE_COMMITTED", database = db)
            val seed = requireNotNull(dao.find("seed"))
            dao.delete("seed")
            dao.upsert(seed.copy(operationId = "host-owned", state = "DEGRADED_COMPLETE", nextRetryAtMs = 200))
            // 跨状态合并以及页游标都必须用 operationId 打破相同队号和时间的平局
            repeat(130) { index ->
                dao.upsert(seed.copy(operationId = "tie-${index.toString().padStart(3, '0')}",
                    queueOrder = 7, createdAtMs = 42,
                    state = if (index % 2 == 0) "CORE_COMMITTED" else "ASSETS_ENRICHING"))
            }
            assertTrue(PostCoreRecoveryReadStore.select(db, 8, emptySet(), true, { false }, 100).isEmpty())
            val attempted = linkedSetOf<String>()
            while (attempted.size < 130) {
                val selected = PostCoreRecoveryReadStore.select(db, 8, attempted, true, { it == "host-owned" }, 100)
                val start = attempted.size
                assertEquals((start until minOf(start + 8, 130)).map { "tie-${it.toString().padStart(3, '0')}" },
                    selected.map { it.operationId })
                attempted += selected.map { it.operationId }
            }
            assertTrue(PostCoreRecoveryReadStore.select(db, 8, attempted, true, { it == "host-owned" }, 100).isEmpty())
        } finally { db.close() }
    }

    @Test fun workerWithoutNetworkKeepsPostCoreOperationUntouched() = runBlocking {
        val offline = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        assertNull(offline.currentDownloadNetworkTypeOrNull())
        val operationId = "offline-post-core-${java.util.UUID.randomUUID()}"
        val dao = NeriUserDataDatabase.getInstance(context).downloadOperationDao()
        GlobalDownloadManager.startupRecoveryMutex.withLock {
            assertTrue(GlobalDownloadManager.assetEnrichmentCoordinator.activeOperationIds().isEmpty())
            try {
                DownloadExecutionRoomStore.upsert(context, DownloadExecutionRequest(
                    operationId = operationId, song = SongItem(1, "test", "artist", "netease", 0, 1000, null),
                    requiresWifiNetwork = false
                ), "CORE_COMMITTED")
                val before = requireNotNull(dao.find(operationId))
                assertEquals(PostCoreDownloadRecoveryResult.WAITING_NETWORK,
                    GlobalDownloadManager.recoverPostCoreDownloadsForWorkerImpl(offline))
                assertEquals(before, dao.find(operationId))
                assertTrue(GlobalDownloadManager.assetEnrichmentCoordinator.activeOperationIds().isEmpty())
            } finally { DownloadExecutionRoomStore.delete(context, operationId) }
        }
    }

    @Test
    fun selectedPayloadReadsStayBoundedAndDrainHasNoGaps() = runBlocking {
        for (size in listOf(128, 1024, 4096)) {
            val queries = CopyOnWriteArrayList<String>()
            val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
                .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
            try {
                val dao = db.downloadOperationDao()
                DownloadExecutionRoomStore.upsert(context, DownloadExecutionRequest(
                    operationId = "seed", song = SongItem(1, "test", "artist", "netease", 0, 1000, null),
                    requiresWifiNetwork = false
                ), "CORE_COMMITTED", database = db)
                val seed = dao.find("seed")!!
                dao.delete("seed")
                db.withTransaction {
                    repeat(size) { i ->
                        dao.upsert(seed.copy(operationId = "op-${i.toString().padStart(5, '0')}",
                            queueOrder = 0, createdAtMs = i.toLong(),
                            sourceHintJson = seed.sourceHintJson.dropLast(1) +
                                ",\"padding\":\"${"x".repeat(if (i < 32) listOf(0, 65536, 131072)[i % 3] else 0)}\"}"))
                    }
                }
                queries.clear()
                if (size == 128) {
                    DownloadExecutionRoomReadStore.listByStatesAnyLibrary(context, PostCoreRecoveryReadStore.states, true, db)
                    val oldReads = queries.count { it.contains("length(source_hint_json)") }
                    assertEquals(size, oldReads)
                    assertTrue(oldReads > 8)
                    Log.i("PostCoreRecoveryProof", "legacy first-window lengthReads=$oldReads exceeds capacity=8")
                    queries.clear()
                }
                val seen = linkedSetOf<String>()
                var first32Reads = -1
                while (true) {
                    val headers = PostCoreRecoveryReadStore.select(db, 8, emptySet(), true, { false })
                    assertTrue(headers.size <= 8)
                    if (headers.isEmpty()) break
                    val ids = headers.map { it.operationId }
                    val entries = PostCoreRecoveryReadStore.entries(context, ids, db)
                    assertEquals(ids.toSet(), entries.map { it.request.operationId }.toSet())
                    assertEquals(ids.size, PostCoreRecoveryReadStore.entries(context, ids, db).size)
                    ids.forEach { assertTrue(seen.add(it)); dao.delete(it) }
                    if (seen.size == 32) {
                        first32Reads = queries.count { it.contains("length(source_hint_json)") }
                        assertEquals(64, first32Reads)
                        val chunks = queries.count { it.contains("substr(source_hint_json") }
                        assertEquals(126, chunks)
                    }
                }
                assertEquals(size, seen.size)
                assertFalse(dao.hasPostCoreBacklog(PostCoreRecoveryReadStore.states))
                Log.i("PostCoreRecoveryProof", "N=$size first32 lengthReads=$first32Reads drained=${seen.size}")
                dao.postCoreHeadersAfter("CORE_COMMITTED", 0, 12, "op-00012", 8)
                val continuationSql = queries.last { it.contains("(queue_order, created_at_ms, operation_id) >") }
                val plan = db.openHelper.readableDatabase.query(
                    androidx.sqlite.db.SimpleSQLiteQuery("EXPLAIN QUERY PLAN $continuationSql",
                        arrayOf<Any>("CORE_COMMITTED", 0, 12, "op-00012", 8))
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString() }
                assertTrue(plan, plan.contains("index_download_operation_recovery_cursor"))
                assertFalse(plan, plan.contains("TEMP B-TREE"))
                Log.i("PostCoreRecoveryProof", plan)
            } finally { db.close() }
        }
    }

    @Test
    fun mobileTailPriorityAndRetryBarrierPreserveSelectionSemantics() = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()
        try {
            val dao = db.downloadOperationDao()
            DownloadExecutionRoomStore.upsert(context, DownloadExecutionRequest(
                operationId = "seed", song = SongItem(1, "test", "artist", "netease", 0, 1000, null),
                requiresWifiNetwork = false
            ), "CORE_COMMITTED", database = db)
            val seed = dao.find("seed")!!
            dao.delete("seed")
            db.withTransaction {
                repeat(4096) { i -> dao.upsert(seed.copy(operationId = "wifi-$i", createdAtMs = i.toLong(),
                    sourceHintJson = seed.sourceHintJson.replace("\"requiresWifiNetwork\":false", "\"requiresWifiNetwork\":true"))) }
                dao.upsert(seed.copy(operationId = "mobile", state = "CORE_COMMITTED", createdAtMs = 9999))
            }
            queries.clear()
            val startedAt = android.os.SystemClock.elapsedRealtime()
            assertEquals(listOf("mobile"), PostCoreRecoveryReadStore.select(db, 8, emptySet(), false, { false }).map { it.operationId })
            val policyPages = queries.count { it.contains("AS requires_wifi_network") }
            assertEquals(65, policyPages)
            assertEquals(0, queries.count { it.contains("length(source_hint_json)") })
            Log.i("PostCoreRecoveryProof", "4096 WIFI prefix mobile tail policyPages=$policyPages headerPages=${queries.count { it.contains("ORDER BY queue_order, created_at_ms, operation_id LIMIT") }} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt} payloadReads=0")
            dao.upsert(seed.copy(operationId = "retry", state = "DEGRADED_COMPLETE", nextRetryAtMs = 200))
            assertTrue(PostCoreRecoveryReadStore.select(db, 8, emptySet(), false, { false }, nowMs = 100).isEmpty())
            assertEquals(listOf("mobile"), PostCoreRecoveryReadStore.select(db, 8, setOf("retry"), false, { false }, nowMs = 100).map { it.operationId })
            dao.delete("retry")
            dao.delete("mobile")
            assertTrue(dao.hasPostCoreBacklog(PostCoreRecoveryReadStore.states))
            assertFalse(dao.hasMobilePostCoreBacklog(PostCoreRecoveryReadStore.states))
            dao.upsert(seed.copy(operationId = "old-spaced", state = "DEGRADED_COMPLETE",
                sourceHintJson = seed.sourceHintJson.replace("\"requiresWifiNetwork\":false", "\"requiresWifiNetwork\": false")))
            dao.upsert(seed.copy(operationId = "old-missing", state = "DEGRADED_COMPLETE",
                sourceHintJson = seed.sourceHintJson.replace("\"requiresWifiNetwork\":false,", "")))
            assertTrue(PostCoreRecoveryReadStore.select(db, 8, emptySet(), false, { false }).isEmpty())
            db.withTransaction { repeat(4096) { dao.delete("wifi-$it") }; dao.delete("old-spaced"); dao.delete("old-missing") }
            db.withTransaction {
                repeat(24) { i -> dao.upsert(seed.copy(operationId = "merge-${i.toString().padStart(2, '0')}",
                    state = if (i % 2 == 0) "CORE_COMMITTED" else "ASSETS_ENRICHING",
                    queueOrder = i / 3, createdAtMs = i.toLong())) }
            }
            val attempted = linkedSetOf<String>()
            repeat(3) {
                val selected = PostCoreRecoveryReadStore.select(db, 8, attempted, true, { false })
                assertEquals((it * 8 until it * 8 + 8).map { i -> "merge-${i.toString().padStart(2, '0')}" }, selected.map { row -> row.operationId })
                attempted += selected.map { row -> row.operationId }
            }
            assertEquals(24, attempted.size)
            assertTrue(PostCoreRecoveryReadStore.select(db, 8, attempted, true, { false }).isEmpty())

        } finally { db.close() }
    }
}
