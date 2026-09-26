package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.download.execution.host.DownloadExecutionRequest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore
import moe.ouom.neriplayer.core.download.manager.admission.stageAndPromotePendingDownloadQueue
import moe.ouom.neriplayer.core.player.download.currentDownloadParallelism
import moe.ouom.neriplayer.core.player.download.publishDownloadParallelism
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadBatchFirstPageTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun largeBatchHandsOffOneTransferWindowBeforePromotingTheTail() = runBlocking {
        withBatch(850, includeLyrics = true) { songs, batch ->
            val pages = mutableListOf<List<String>>()
            var firstPageMs = 0L
            val started = SystemClock.elapsedRealtime()
            val staged = GlobalDownloadManager.stageAndPromotePendingDownloadQueue(
                context, songs, userInitiated = true, batchIdentity = batch
            ) { _, page ->
                pages += page.operationIds
                if (pages.size == 1) firstPageMs = SystemClock.elapsedRealtime() - started
                val headers = DownloadExecutionRoomStore.readOperationHeaders(context, page.operationIds)
                assertEquals(page.operationIds.size, headers.size)
                assertTrue(headers.values.all {
                    it.state == "QUEUED" && it.batchId == batch.batchId &&
                        it.batchGeneration == batch.generation
                })
            }
            println("DOWNLOAD_FIRST_PAGE_BENCHMARK songs=850 lyrics=true firstPageMs=$firstPageMs " +
                "totalMs=${SystemClock.elapsedRealtime() - started} pageSizes=${pages.map { it.size }}")
            assertEquals("首个交接只需准备当前 6 个传输名额", 6, pages.first().size)
            assertTrue(pages.drop(1).all { it.size <= 64 })
            assertEquals(850, staged.operationIds.distinct().size)
            assertEquals(staged.operationIds, pages.flatten())
            staged.operationIds.forEachIndexed { index, id ->
                val original = songs[index]
                val restored = checkNotNull(DownloadExecutionRoomStore.read(context, id)).song
                assertEquals(original.copy(sourceStableKey = original.stableKey()), restored)
            }
        }
    }

    @Test
    fun interruptedHandoffKeepsTheWholeSelectionRecoverable() = runBlocking {
        withBatch(70) { songs, batch ->
            val songKeys = songs.map(SongItem::stableKey)
            GlobalDownloadManager.cancellationForceNewSongKeys.addAll(songKeys)
            var handedOff = emptyList<String>()
            try {
                GlobalDownloadManager.stageAndPromotePendingDownloadQueue(
                    context, songs, userInitiated = true, batchIdentity = batch
                ) { _, page ->
                    handedOff = page.operationIds
                    throw CancellationException("interrupt after durable handoff")
                }
            } catch (_: CancellationException) {
                // 模拟交接后准备协程中断，重新从持久化入口读取整批
            }
            assertEquals(6, handedOff.size)
            assertFalse("整批新意图落库后不能遗留尾部的强制新建标记",
                songKeys.any(GlobalDownloadManager.cancellationForceNewSongKeys::contains))
            val db = NeriUserDataDatabase.getInstance(context)
            val reopened = Room.databaseBuilder(context, NeriUserDataDatabase::class.java,
                checkNotNull(db.openHelper.databaseName)).build()
            try {
                val members = reopened.downloadBatchDao().listMembers(batch.batchId)
                val operationIds = members.mapNotNull { it.operationId }
                assertEquals("首批交接后中断不能丢失尾部歌曲的下载信息", 70, operationIds.size)
                val restored = DownloadExecutionRoomStore.readOperationSnapshots(context, operationIds, reopened)
                assertEquals(operationIds.toSet(), restored.keys)
                assertEquals(songs.map(SongItem::stableKey), operationIds.map { id ->
                    checkNotNull(restored[id]).request.song.stableKey()
                })
                assertTrue(restored.values.all { it.request.userInitiated })
                val headers = DownloadExecutionRoomStore.readOperationHeaders(context, operationIds, reopened)
                assertTrue(handedOff.all { headers[it]?.state == "QUEUED" })
                val tail = operationIds.drop(6)
                assertTrue(tail.all { headers[it]?.state == "WAITING_STORAGE_MUTATION" })
                assertEquals(64, DownloadExecutionRoomStore.promoteWaitingStorageMutations(context, tail, reopened))
                assertTrue(DownloadExecutionRoomStore.readOperationHeaders(context, operationIds, reopened)
                    .values.all { it.state == "QUEUED" })
                val replay = GlobalDownloadManager.stageAndPromotePendingDownloadQueue(
                    context, songs, userInitiated = true, batchIdentity = batch
                )
                assertEquals("同进程中断后重入应复用已落库的整批任务", operationIds, replay.operationIds)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun interruptedHandoffBindsReusedTailWithoutLosingAttemptOrResumeData() = runBlocking {
        withBatch(70) { songs, batch ->
            val states = listOf("WAITING_STORAGE_MUTATION", "QUEUED", "RUNNING")
            val reused = songs.takeLast(3).mapIndexed { index, song ->
                DownloadExecutionRequest(
                    operationId = UUID.randomUUID().toString(), song = song,
                    preserveStaging = true, requiresWifiNetwork = true, userInitiated = false,
                    attemptId = 100L + index, artifactLeaseId = UUID.randomUUID().toString()
                ).also { request ->
                    DownloadExecutionRoomStore.upsert(context, request, states[index], queueOrder = index)
                }
            }
            try {
                GlobalDownloadManager.stageAndPromotePendingDownloadQueue(
                    context, songs, userInitiated = true, batchIdentity = batch
                ) { _, _ -> throw CancellationException("interrupt before reused tail promotion") }
            } catch (_: CancellationException) {
                // 复用的尾部任务也必须在首批交接前绑定，而不是依赖后续分页
            }
            val members = NeriUserDataDatabase.getInstance(context)
                .downloadBatchDao().listMembers(batch.batchId)
            assertEquals(70, members.mapNotNull { it.operationId }.size)
            reused.forEachIndexed { index, original ->
                val restored = checkNotNull(DownloadExecutionRoomStore.read(context, original.operationId))
                assertEquals(original.operationId, members.single {
                    it.stableKey == original.song.stableKey()
                }.operationId)
                assertEquals(batch.batchId, restored.batchId)
                assertEquals(batch.generation, restored.batchGeneration)
                assertEquals(original.attemptId, restored.attemptId)
                assertEquals(original.artifactLeaseId, restored.artifactLeaseId)
                assertTrue(restored.preserveStaging)
                assertTrue(restored.userInitiated)
                assertEquals(states[index], DownloadExecutionRoomStore.state(context, original.operationId))
            }
        }
    }

    private suspend fun withBatch(
        count: Int,
        includeLyrics: Boolean = false,
        block: suspend (List<SongItem>, DownloadExecutionRoomStore.DownloadBatchIdentity) -> Unit
    ) {
        // 固定队列状态供断言使用，避免应用启动恢复与测试准备并发消费同一批 fixture
        val gate = GlobalDownloadManager.downloadAdmissionGate
        val token = gate.beginClear()
        check(token.ownsClear)
        gate.runClear(token) {
            gate.awaitIdle()
            withQuiescentBatch(count, includeLyrics, block)
        }
    }

    private suspend fun withQuiescentBatch(
        count: Int,
        includeLyrics: Boolean,
        block: suspend (List<SongItem>, DownloadExecutionRoomStore.DownloadBatchIdentity) -> Unit
    ) {
        val previousParallelism = currentDownloadParallelism(context)
        val id = SystemClock.elapsedRealtimeNanos()
        val runId = UUID.randomUUID().toString()
        val songs = List(count) { index -> SongItem(
            id = id + index, name = "first-page-$runId-$index", artist = "fixture",
            album = "netease", albumId = 0L, durationMs = 1_000L, coverUrl = null,
            originalLyric = if (includeLyrics) "original lyric line\n".repeat(128) else null,
            originalTranslatedLyric = if (includeLyrics) "translated lyric line\n".repeat(128) else null,
            originalRomanizedLyric = if (includeLyrics) "romanized lyric line\n".repeat(128) else null
        ) }
        val db = NeriUserDataDatabase.getInstance(context)
        val batch = DownloadExecutionRoomStore.createBatchSnapshot(context, songs)
        try {
            publishDownloadParallelism(6)
            block(songs, batch)
        } finally {
            publishDownloadParallelism(previousParallelism)
            songs.forEach { GlobalDownloadManager.cancellationForceNewSongKeys.remove(it.stableKey()) }
            val ids = db.downloadBatchDao().listMembers(batch.batchId).mapNotNull { it.operationId }
            DownloadExecutionRoomStore.deleteOperationsWithAdmissions(db, ids)
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM download_batch_member WHERE batch_id = ?", arrayOf(batch.batchId)
            )
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM download_batch WHERE batch_id = ?", arrayOf(batch.batchId)
            )
        }
    }
}
