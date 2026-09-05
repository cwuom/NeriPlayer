package moe.ouom.neriplayer.core.player.download

import android.content.Context
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.core.download.GlobalDownloadManager
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.displayName
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import java.util.concurrent.atomic.AtomicInteger

/**
 * 批量下载协调器
 *
 * 调度、进度聚合和回调收尾不再和单曲传输状态混在一个对象里。协调器只通过
 * hooks 操作主类的状态，保留每首歌曲的锁和 attempt 边界
 */
internal class AudioDownloadBatchCoordinator(
    private val latestProgressByOperation: StateFlow<
        Map<String, AudioDownloadManager.DownloadProgress>
    >,
    private val latestProgressEvents: SharedFlow<AudioDownloadManager.DownloadProgress>,
    private val maxCompletionCallbacks: Int,
    private val hooks: Hooks,
    private val tag: String = "NERI-Downloader"
) {
    internal interface Hooks {
        fun startBatchSession(): Long

        fun isBatchSessionCurrent(batchSessionId: Long?): Boolean

        fun finishBatchSession(batchSessionId: Long)

        fun updateBatchProgressForSession(
            batchSessionId: Long,
            progress: AudioDownloadManager.BatchDownloadProgress?
        )

        fun isAllDownloadsCancelled(): Boolean

        fun resetCancelFlag()

        fun resolveWorkerCount(songCount: Int, requestedParallelism: Int): Int

        fun shouldPreserveArtifactsForNetworkPolicy(songKey: String): Boolean

        suspend fun downloadSong(
            context: Context,
            song: SongItem,
            batchSessionId: Long,
            attemptId: Long?
        )
    }

    private data class TrackedBatchSong(
        val song: SongItem,
        val index: Int
    )

    private enum class BatchAdmission {
        START,
        CANCELLED,
        PAUSED,
        STALE
    }

    suspend fun downloadPlaylist(
        context: Context,
        songs: List<SongItem>,
        maxConcurrentDownloads: Int,
        songAttemptIds: Map<String, Long>,
        onSongStarted: suspend (SongItem) -> Unit,
        onSongCompleted: suspend (SongItem) -> Unit,
        onSongFailed: suspend (SongItem, Throwable) -> Unit,
        onSongCancelled: suspend (SongItem) -> Unit,
        onSongPausedForNetworkPolicy: suspend (SongItem) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var batchSessionId: Long? = null
            try {
                batchSessionId = hooks.startBatchSession()
                val remoteSongs = songs.filterNot { LocalSongSupport.isLocalSong(it, context) }
                if (remoteSongs.isEmpty()) {
                    NPLogger.d(tag, "Skip batch download because all songs are local")
                    hooks.updateBatchProgressForSession(batchSessionId, null)
                    return@withContext
                }

                val trackedSongs = remoteSongs.mapIndexed { index, song ->
                    TrackedBatchSong(song = song, index = index)
                }
                val trackedSongByKey = trackedSongs.associateBy { it.song.stableKey() }
                val progressMutex = Mutex()
                val latestProgressBySongKey = mutableMapOf<
                    String,
                    AudioDownloadManager.DownloadProgress
                >()
                val progressPublishMutex = Mutex()
                var completedSongs = 0
                var currentSongLabel = ""
                var currentSongIndex = 0
                var nextProgressVersion = 0L
                var publishedProgressVersion = 0L

                suspend fun publishBatchProgress() {
                    val snapshot = progressMutex.withLock {
                        val leadingEntry = latestProgressBySongKey.entries
                            .minByOrNull { entry -> trackedSongByKey.getValue(entry.key).index }
                        if (leadingEntry != null) {
                            val trackedSong = trackedSongByKey.getValue(leadingEntry.key)
                            currentSongLabel = trackedSong.song.displayName()
                            currentSongIndex = trackedSong.index
                        }
                        val aggregateProgressFraction = if (trackedSongs.isEmpty()) {
                            1.0
                        } else {
                            (
                                completedSongs.toDouble() +
                                    latestProgressBySongKey.values.sumOf { progress ->
                                        if (progress.totalBytes > 0L) {
                                            progress.bytesRead.toDouble() /
                                                progress.totalBytes.toDouble()
                                        } else {
                                            0.0
                                        }
                                }
                            ) / trackedSongs.size.toDouble()
                        }.coerceIn(0.0, 1.0).toFloat()

                        nextProgressVersion += 1
                        nextProgressVersion to AudioDownloadManager.BatchDownloadProgress(
                            totalSongs = trackedSongs.size,
                            completedSongs = completedSongs,
                            currentSong = currentSongLabel,
                            currentProgress = leadingEntry?.value,
                            currentSongIndex = currentSongIndex,
                            aggregateProgressFraction = aggregateProgressFraction
                        )
                    }

                    // 外部 Flow 发布不占用聚合锁，版本号避免并发快照倒序覆盖
                    progressPublishMutex.withLock {
                        val (version, progress) = snapshot
                        if (
                            version > publishedProgressVersion &&
                                hooks.isBatchSessionCurrent(batchSessionId)
                        ) {
                            hooks.updateBatchProgressForSession(batchSessionId, progress)
                            publishedProgressVersion = version
                        }
                    }
                }

                suspend fun markSongStarted(trackedSong: TrackedBatchSong) {
                    progressMutex.withLock {
                        currentSongLabel = trackedSong.song.displayName()
                        currentSongIndex = trackedSong.index
                    }
                    publishBatchProgress()
                }

                suspend fun markSongFinished(songKey: String) {
                    progressMutex.withLock {
                        latestProgressBySongKey.remove(songKey)
                        completedSongs++
                    }
                    publishBatchProgress()
                }

                suspend fun markSongPaused(songKey: String) {
                    progressMutex.withLock {
                        latestProgressBySongKey.remove(songKey)
                    }
                    publishBatchProgress()
                }

                hooks.resetCancelFlag()
                hooks.updateBatchProgressForSession(
                    batchSessionId,
                    AudioDownloadManager.BatchDownloadProgress(
                        totalSongs = trackedSongs.size,
                        completedSongs = 0,
                        currentSong = "",
                        currentProgress = null,
                        aggregateProgressFraction = 0f
                    )
                )
                val workerCount = hooks.resolveWorkerCount(
                    songCount = trackedSongs.size,
                    requestedParallelism = maxConcurrentDownloads
                )

                suspend fun acceptProgress(progress: AudioDownloadManager.DownloadProgress) {
                    if (!hooks.isBatchSessionCurrent(batchSessionId)) {
                        return
                    }
                    if (!trackedSongByKey.containsKey(progress.songKey)) {
                        return
                    }
                    val expectedAttemptId = songAttemptIds[progress.songKey]
                    if (
                        expectedAttemptId != null &&
                            progress.attemptId != expectedAttemptId
                    ) {
                        return
                    }
                    progressMutex.withLock {
                        val previous = latestProgressBySongKey[progress.songKey]
                        latestProgressBySongKey[progress.songKey] =
                            AudioDownloadProgressPolicy.mergeLatestProgress(previous, progress)
                    }
                    publishBatchProgress()
                }

                // 快照只作为有界补偿，增量事件负责低延迟更新，避免为每个事件扫描整图
                val progressSnapshotJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    latestProgressByOperation.collect { latestProgress ->
                        latestProgress.values.forEach { progress ->
                            acceptProgress(progress)
                        }
                    }
                }
                val progressJob = launch(start = CoroutineStart.UNDISPATCHED) {
                    latestProgressEvents.collect { progress ->
                        acceptProgress(progress)
                    }
                }
                val completionDispatcher = BatchDownloadCompletionDispatcher(
                    scope = this,
                    maxConcurrentCallbacks = maxCompletionCallbacks
                )

                suspend fun processTrackedSong(trackedSong: TrackedBatchSong) {
                    val song = trackedSong.song
                    val songKey = song.stableKey()
                    val attemptId = songAttemptIds[songKey]
                    var admission = BatchAdmission.STALE
                    GlobalDownloadManager.withSongExecutionLock(songKey) {
                        admission = when {
                            hooks.isAllDownloadsCancelled() ||
                                !hooks.isBatchSessionCurrent(batchSessionId) -> {
                                NPLogger.d(
                                    tag,
                                    context.getString(R.string.download_cancelled_message)
                                )
                                BatchAdmission.CANCELLED
                            }
                            GlobalDownloadManager.isSongCancelled(songKey) -> {
                                NPLogger.d(tag, "跳过已取消的歌曲: ${song.name}")
                                GlobalDownloadManager.clearSongCancelled(songKey)
                                BatchAdmission.CANCELLED
                            }
                            hooks.shouldPreserveArtifactsForNetworkPolicy(songKey) -> {
                                BatchAdmission.PAUSED
                            }
                            !GlobalDownloadManager.isDownloadAttemptActive(songKey, attemptId) -> {
                                NPLogger.d(tag, "跳过过期的批量下载项: ${song.name}")
                                BatchAdmission.STALE
                            }
                            else -> BatchAdmission.START
                        }
                    }

                    when (admission) {
                        BatchAdmission.CANCELLED -> {
                            markSongFinished(songKey)
                            invokeBatchCallback(song) { onSongCancelled(song) }
                            return
                        }
                        BatchAdmission.PAUSED -> {
                            markSongPaused(songKey)
                            invokeBatchCallback(song) {
                                onSongPausedForNetworkPolicy(song)
                            }
                            return
                        }
                        BatchAdmission.STALE -> {
                            markSongFinished(songKey)
                            return
                        }
                        BatchAdmission.START -> Unit
                    }

                    var queuedCompletion: (suspend () -> Unit)? = null
                    var pausedForNetworkPolicy = false
                    try {
                        markSongStarted(trackedSong)
                        invokeBatchCallback(song) { onSongStarted(song) }
                        hooks.downloadSong(
                            context = context,
                            song = song,
                            batchSessionId = batchSessionId,
                            attemptId = attemptId
                        )
                        queuedCompletion = {
                            try {
                                // 完成回调不持有歌曲锁，避免回调重新入队时自锁
                                invokeBatchCallback(song) { onSongCompleted(song) }
                            } finally {
                                markSongFinished(songKey)
                            }
                        }
                    } catch (_: java.util.concurrent.CancellationException) {
                        NPLogger.d(tag, "歌曲下载被取消: ${song.name}")
                        if (hooks.shouldPreserveArtifactsForNetworkPolicy(songKey)) {
                            pausedForNetworkPolicy = true
                            markSongPaused(songKey)
                            invokeBatchCallback(song) {
                                onSongPausedForNetworkPolicy(song)
                            }
                        } else {
                            GlobalDownloadManager.clearSongCancelled(songKey)
                            invokeBatchCallback(song) { onSongCancelled(song) }
                        }
                    } catch (error: Exception) {
                        NPLogger.e(
                            tag,
                            context.getString(
                                R.string.download_batch_failed_song,
                                song.name,
                                error.message ?: ""
                            ),
                            error
                        )
                        invokeBatchCallback(song) { onSongFailed(song, error) }
                    } finally {
                        if (queuedCompletion == null && !pausedForNetworkPolicy) {
                            markSongFinished(songKey)
                        }
                    }

                    // 回调不在歌曲锁内等待收尾队列，避免慢回调反向阻塞下载发送方
                    val completion = queuedCompletion
                    if (completion != null) {
                        try {
                            completionDispatcher.dispatch(completion)
                        } catch (error: Throwable) {
                            // 入队失败时由当前协程补一次最终计数
                            markSongFinished(songKey)
                            throw error
                        }
                    }
                }

                try {
                    // 单首回调异常不能取消同批其他歌曲
                    supervisorScope {
                        val nextSongIndex = AtomicInteger(0)
                        List(workerCount) {
                            launch {
                                while (true) {
                                    val songIndex = nextSongIndex.getAndIncrement()
                                    if (songIndex >= trackedSongs.size) {
                                        break
                                    }
                                    processTrackedSong(trackedSongs[songIndex])
                                }
                            }
                        }.joinAll()
                        completionDispatcher.awaitAll()
                    }
                } finally {
                    // 先等进度收集器退出，再结束 session，避免旧批次回调污染下一批
                    withContext(kotlinx.coroutines.NonCancellable) {
                        progressJob.cancelAndJoin()
                        progressSnapshotJob.cancelAndJoin()
                    }
                }

                hooks.updateBatchProgressForSession(batchSessionId, null)
            } catch (cancellation: java.util.concurrent.CancellationException) {
                batchSessionId?.let { hooks.updateBatchProgressForSession(it, null) }
                throw cancellation
            } catch (error: Exception) {
                NPLogger.e(
                    tag,
                    context.getString(R.string.download_batch_failed, error.message ?: ""),
                    error
                )
                batchSessionId?.let { hooks.updateBatchProgressForSession(it, null) }
            } finally {
                batchSessionId?.let(hooks::finishBatchSession)
            }
        }
    }

    private suspend fun invokeBatchCallback(
        song: SongItem,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (cancellation: java.util.concurrent.CancellationException) {
            throw cancellation
        } catch (callbackError: Exception) {
            NPLogger.e(tag, "批量下载回调失败: ${song.name}", callbackError)
        }
    }

}
